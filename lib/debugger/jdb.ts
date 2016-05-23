'use strict';

import {readFileSync} from 'fs';
import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs';
import * as child_process from 'child_process';
import * as StringDecoder from 'string_decoder';
import {DebugSession, InitializedEvent, TerminatedEvent, StoppedEvent, OutputEvent, Thread, StackFrame, Scope, Source, Handles} from 'vscode-debugadapter';
const getport = require("get-port");
import {LaunchRequestArguments} from './contracts';

export const MAIN_THREAD_ID = 1;
export const MAIN_THREAD_NAME = "main";
const MAIN_THREAD_PREFIX = "main[1]";
const JAVA_FX_THREAD_NAME = "JavaFX Application Thread";
const JAVA_APPLICATION_EXITED = "The application exited";

interface IJdbRunnerCommand {
    commandLine: string
    promise: Promise<string[]>
    promiseResolve: (data: string[]) => void
}

function stringEndsWith(value:string, suffix:string){
    return value.indexOf(suffix, value.length - suffix.length) !== -1; 
}

/*
How to start the java server
1. java -agentlib:jdwp=transport=dt_socket,server=y,address=3003 DrawCards
2. jdb -connect com.sun.jdi.SocketAttach:hostname=localhost,port=3003
*/
export class JdbRunner {
    public jdbLoaded: Promise<any>;
    public javaLoaded: Promise<any>;
    public readyToAcceptCommands: boolean;

    private jdbProc: child_process.ChildProcess;
    private javaProc: child_process.ChildProcess;
    private debugSession: DebugSession;
    private className: string;
    public Exited: Promise<any>;
    private exitedResolve: () => void;
    public constructor(private sourceFile: string, private args: LaunchRequestArguments, debugSession: DebugSession) {
        this.debugSession = debugSession;
        this.className = path.basename(this.sourceFile);
        this.className = this.className.substring(0, this.className.length - path.extname(this.className).length);
        this.jdbLoaded = new Promise<any>((resolve) => {
            this.jdbLoadedResolve = resolve;
        });

        this.startProgramInDebugJavaMode().then(port => {
            this.initProc(port);
        });
        this.Exited = new Promise<any>((resolve, reject) => {
            this.exitedResolve = resolve;
        });
    }

    private sendRemoteConsoleLog(msg) {
        this.debugSession.sendEvent(new OutputEvent(msg));
    }

    private initProc(port: number) {
        var fileDir = path.dirname(this.sourceFile);
        var jdbPath = this.args.jdkPath ? path.join(this.args.jdkPath, "jdb") : "jdb";
        var args = ["-connect", `com.sun.jdi.SocketAttach:hostname=localhost,port=${port}`];
        // if (this.args.externalConsole === true) {
        //     open({ wait: false, app: [jdbPath].concat(args), cwd: fileDir }).then(proc=> {
        //         this.jdbProc = proc;
        //     }, error=> {
        //         if (!this.debugServer && this.debugServer.IsRunning) {
        //             return;
        //         }
        //         this.displayError(error);
        //     });

        //     return;
        // }
        this.jdbProc = child_process.spawn(jdbPath, args, {
            cwd: fileDir
        });

        var that = this;
        this.jdbProc.stdout.on("data", (data) => {
            that.onDataReceived(data);
        });
        this.jdbProc.stdout.on("error", (data) => {
            that.sendRemoteConsoleLog("Jdb Error " + data);
        });
        this.jdbProc.stdout.on("exit", (data) => {
        });
        this.jdbProc.stdout.on("close", (data) => {
            that.onDataReceived("", true);
        });
        this.jdbProc.stderr.on("data", (data) => {
            that.sendRemoteConsoleLog("Jdb Error Data" + data);
        });
    }

    private javaServerAppStarted: boolean;
    private startProgramInDebugJavaMode(): Promise<number> {
        return getport().then((port: number) => {
            var fileDir = path.dirname(this.sourceFile);
            var javaPath = (!this.args.jdkPath || this.args.jdkPath.length === 0) ? "java" : path.join(this.args.jdkPath, "java");
            var args = [`-agentlib:jdwp=transport=dt_socket,server=y,address=${port}`].concat(this.args.options).concat(this.className);
            this.javaProc = child_process.spawn(javaPath, args, {
                cwd: fileDir
            });

            this.javaLoaded = new Promise<number>((resolve) => {
                this.javaLoadedResolve = resolve;
            });

            //read the jdb output
            var that = this;
            var accumulatedData = "";
            this.javaProc.stdout.on("data", (data) => {
                var dataStr = new Buffer(data).toString('utf-8');
                if (this.javaServerAppStarted) {
                    if (!this.args.externalConsole) {
                        that.debugSession.sendEvent(new OutputEvent(dataStr));
                    }
                }
                else {
                    accumulatedData += dataStr;
                    if (accumulatedData.indexOf("Listening for transport") === 0 && stringEndsWith(accumulatedData.trim(), port.toString())) {
                        accumulatedData = "";
                        this.javaServerAppStarted = true;
                        this.javaLoadedResolve(port);
                    }
                }
            });
            this.javaProc.stdout.on("error", (data) => {
                that.debugSession.sendEvent(new OutputEvent("Java Error" + data, "error"));
            });
            this.javaProc.stdout.on("exit", (data) => {
            });
            this.javaProc.stdout.on("close", (data) => {
                that.onDataReceived("", true);
            });
            this.javaProc.stderr.on("data", (data) => {
                that.debugSession.sendEvent(new OutputEvent("Java Error Data" + data, "error"));
            });
            return this.javaLoaded;
        });
    }

    public sendCmd(command: string): Promise<string[]> {
        if (this.exited) {
            return Promise.resolve([]);
        }
        return new Promise<string[]>(resolve => {
            this.jdbLoaded.then(() => {
                var jdbCmd: IJdbRunnerCommand = <IJdbRunnerCommand>{ commandLine: command };
                jdbCmd.promise = new Promise<string[]>(resolve => {
                    jdbCmd.promiseResolve = resolve;
                });
                jdbCmd.promise.then(resolve);

                this.pendingCommands.push(jdbCmd);
                this.checkAndSendCommand();
            });
        });
    }

    private checkAndSendCommand() {
        if (this.exited) {
            this.pendingCommands.forEach(cmd => {
                cmd.promiseResolve([]);
            });
            return;
        }

        if (this.executingCommands.length === 0) {
            if (this.pendingCommands.length > 0) {
                var jdbCmd = this.pendingCommands[0];
                this.executingCommands.push(jdbCmd);
                this.jdbProc.stdin.write(jdbCmd.commandLine + "\n");
            }
            return;
        }

        var currentCmd = this.executingCommands[0];
        currentCmd.promise.then(() => {
            this.checkAndSendCommand();
        });
    }

    private outputBuffer: string = "";
    private stringDecoder = new StringDecoder.StringDecoder('utf8');
    private pendingCommands: IJdbRunnerCommand[] = [];
    private executingCommands: IJdbRunnerCommand[] = [];
    private jdbLoadedResolve: () => void;
    private javaLoadedResolve: (number) => void;
    private stopAtInitSent: boolean;
    private stopAtCliInitSent: boolean;
    private stopAtMainSent: boolean;
    private runSent: boolean;
    private exited: boolean;
    private onDataReceived(data, exit: boolean = false) {
        this.outputBuffer = this.outputBuffer + new Buffer(data).toString('utf-8');
        var lines = this.outputBuffer.split(/(\r?\n)/g).filter(line => line !== os.EOL && line !== "\n" && line !== "\r");
        if (lines.length === 0) {
            return;
        }

        var lastLine = lines[lines.length - 1];

        if (this.executingCommands.length === 0 && stringEndsWith(lastLine.trim(), MAIN_THREAD_PREFIX) && !this.readyToAcceptCommands) {
            if (!this.stopAtInitSent) {
                //Add the break point to the first entry point
                this.stopAtInitSent = true;
                this.jdbProc.stdin.write(`stop in ${this.className}.<init>\n`);
                return;
            }
            if (!this.stopAtCliInitSent) {
                //Add the break point to the first entry point
                this.stopAtCliInitSent = true;
                this.jdbProc.stdin.write(`stop in ${this.className}.<cliinit>\n`);
                return;
            }
            if (!this.stopAtMainSent) {
                //Add the break point to the first entry point
                this.stopAtMainSent = true;
                this.jdbProc.stdin.write(`stop in ${this.className}.main\n`);
                return;
            }
            if (!this.runSent) {
                //Add the break point to the first entry point
                this.runSent = true;
                this.jdbProc.stdin.write(`run\n`);
                return;
            }
        }
        if (this.executingCommands.length === 0 &&
            (stringEndsWith(lastLine.trim(), MAIN_THREAD_PREFIX) || lastLine.trim().indexOf(JAVA_FX_THREAD_NAME) === 0) &&
            !this.readyToAcceptCommands) {

            this.outputBuffer = "";
            this.readyToAcceptCommands = true;
            this.jdbLoadedResolve.call(this);
            return;
        }
        if (this.executingCommands.length === 0 &&
            (stringEndsWith(lastLine.trim(), JAVA_APPLICATION_EXITED) || 
            (lines.length >= 2 && stringEndsWith(lines[lines.length - 2].trim(), JAVA_APPLICATION_EXITED))) &&
            !this.readyToAcceptCommands) {
 
            this.outputBuffer = "";
            this.readyToAcceptCommands = true;
            this.jdbLoadedResolve.call(this);
        
            this.exited = true;
            this.exitedResolve();            
            return;
        }

        if (this.executingCommands.length === 0) {
            return;
        }
        var lastCmd = this.executingCommands[this.executingCommands.length - 1];
        var isEndOfLine = (lastLine.trim() === MAIN_THREAD_PREFIX || lastLine.trim().indexOf(JAVA_FX_THREAD_NAME) === 0);

        if (isEndOfLine || exit === true) {
            this.outputBuffer = "";

            //Remove main[1] prompt
            lines.pop();
            //If the application exits, the the last line is "the application exited" message followed by a new line
            if (exit) {
                lines.pop();
            }

            this.pendingCommands.shift();
            this.executingCommands.pop();
            lastCmd.promiseResolve(lines);

            if (exit) {
                this.exited = true;
                this.exitedResolve();
            }
        }
    }
}