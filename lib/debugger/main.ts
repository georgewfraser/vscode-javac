'use strict';
 
import {DebugSession, InitializedEvent, TerminatedEvent, StoppedEvent, OutputEvent, Thread, StackFrame, Scope, Source, Handles} from 'vscode-debugadapter';
import {DebugProtocol} from 'vscode-debugprotocol';
import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs';
import * as child_process from 'child_process';
import {JdbRunner, MAIN_THREAD_ID, MAIN_THREAD_NAME} from './jdb';
import {LaunchRequestArguments, IJavaEvaluationResult, IJavaStackFrame, IJavaThread, JavaEvaluationResultFlags, IDebugVariable, ICommand, IStackInfo} from './contracts';

interface ICommandToExecute {
    name: string
    command?: string
    responseProtocol?: DebugProtocol.Response
}

class JavaDebugSession extends DebugSession {

    private _variableHandles: Handles<IDebugVariable>;
    private commands: ICommand[] = [];
    private _sourceFile: string;
    private _breakPoints: any;


    public constructor(debuggerLinesStartAt1: boolean, isServer: boolean) {
        super(debuggerLinesStartAt1, isServer === true);
        this._sourceFile = null;
        this._breakPoints = {};
        this._variableHandles = new Handles<IDebugVariable>();
    }

    protected initializeRequest(response: DebugProtocol.InitializeResponse, args: DebugProtocol.InitializeRequestArguments): void {
        this.sendResponse(response);

        // now we are ready to accept breakpoints -> fire the initialized event to give UI a chance to set breakpoints
        this.sendEvent(new InitializedEvent());
    }

    private validFiles = {};
    private parseWhere(data: string): IStackInfo {
        var currentStack = <IStackInfo>{};
        var fileName = data.substring(data.lastIndexOf("(") + 1, data.lastIndexOf(":"));
        var line = data.substring(data.lastIndexOf(":") + 1, data.lastIndexOf(")"));
        var fullFileName = fileName;
        if (this.validFiles[fileName]) {
            fullFileName = path.join(this.rootDir, fileName);
        }
        else {
            fullFileName = path.join(this.rootDir, fileName);
            if (fs.existsSync(fullFileName)) {
                this.validFiles[fileName] = true;
            }
            else {
                fullFileName = fileName === "null" ? "" : fileName;
            }
        }
        currentStack.fileName = fullFileName;
        currentStack.lineNumber = parseInt(line);
        currentStack["function"] = data.substring(data.indexOf("]") + 1, data.lastIndexOf("(")).trim();
        currentStack.source = data;
        return currentStack;
    }
    private jdbRunner: JdbRunner;
    private rootDir: string;
    private launchResponse: DebugProtocol.LaunchResponse;
    protected launchRequest(response: DebugProtocol.LaunchResponse, args: LaunchRequestArguments): void {
        this._sourceFile = args.program;
        this.launchResponse = response;
        this.rootDir = path.dirname(this._sourceFile);

        this.jdbRunner = new JdbRunner(this._sourceFile, args, this);
 
        this.jdbRunner.jdbLoaded.then(() => {
            this.sendResponse(this.launchResponse);
            this.sendEvent(new StoppedEvent("entry", MAIN_THREAD_ID));
        });

        this.jdbRunner.Exited.then(() => {
            this.sendEvent(new TerminatedEvent());
        });
    }

    protected setBreakPointsRequest(response: DebugProtocol.SetBreakpointsResponse, args: DebugProtocol.SetBreakpointsArguments): void {
        this.jdbRunner.jdbLoaded.then(() => {
            var fileName = path.basename(args.source.path);
            fileName = fileName.substring(0, fileName.length - path.extname(fileName).length);
            var promises = args.breakpoints.map(bk => {
                return this.jdbRunner.sendCmd(`stop at ${fileName}:${bk.line}`).then(() => {
                    return bk.line;
                });
            });
            Promise.all(promises).then(verifiedLines => {
                // send back the actual breakpoints
                response.body = {
                    breakpoints: []
                };
                verifiedLines.forEach(line => {
                    response.body.breakpoints.push({ verified: true, line: <number><any>line });
                });
                this.sendResponse(response);
                this.sendEvent(new StoppedEvent("breakpoint", MAIN_THREAD_ID));
            });
        });
    }

    protected threadsRequest(response: DebugProtocol.ThreadsResponse): void {

        // return the default thread
        response.body = {
            threads: [
                new Thread(MAIN_THREAD_ID, MAIN_THREAD_NAME)
            ]
        };
        this.sendResponse(response);
    }

    private parseStackTrace(data: string[]): IStackInfo[] {
        var stackInfo: IStackInfo[] = [];
        data.forEach(line => {
            if (line.trim().length > 0 && line.indexOf(":") > 0 && line.indexOf("(") > 0) {
                stackInfo.push(this.parseWhere(line));
            }
        });

        return stackInfo;
    }

    private refreshStackInfo: boolean = true;

    protected stackTraceRequest(response: DebugProtocol.StackTraceResponse, args: DebugProtocol.StackTraceArguments): void {
        if (!this.jdbRunner.readyToAcceptCommands) {
            return
        }
        this.jdbRunner.jdbLoaded.then(() => {
            this.jdbRunner.sendCmd("where").then((data) => {
                this.refreshStackInfo = false;
                const frames = new Array<StackFrame>();
                this.parseStackTrace(data).forEach((stackInfo, i) => {
                    var name = stackInfo.function;
                    frames.push(new StackFrame(i, `${name}(${i})`,
                        new Source(stackInfo.fileName, this.convertDebuggerPathToClient(stackInfo.fileName)),
                        this.convertDebuggerLineToClient(stackInfo.lineNumber - 1),
                        0));
                });

                response.body = {
                    stackFrames: frames
                };
                this.sendResponse(response);
            });
        });
    }

    private getVariableValue(variableName: string): Promise<{ printedValue: string, dumpValue: string, dumpLines: string[] }> {
        var printedPromise = this.jdbRunner.sendCmd("print " + variableName).then(data => {
            if (data.length === 0 || data[0].length === 0) {
                throw "Invalid";
            }
            if (data.length === 2 && data[0].indexOf(variableName) !== 0 && data[0].indexOf("ParseException: Name unknown: ") > 0) {
                throw "Invalid";
            }

            return data.join("").substring(` ${variableName} = `.length);
        });
        var dumpPromise = this.jdbRunner.sendCmd("dump " + variableName).then(data => {
            if (data.length === 0 || data[0].length === 0) {
                throw "Invalid";
            }
            if (data.length === 2 && data[0].indexOf(variableName) !== 0 && data[0].indexOf("ParseException: Name unknown: ") > 0) {
                throw "Invalid";
            }
 
            data[0] = data[0].substring(` ${variableName} = `.length);
            return [data.join(""), data];
        });

        return Promise.all<any>([printedPromise, dumpPromise]).then(values => {
            var stringValues = values;
            return { printedValue: stringValues[0], dumpValue: stringValues[1][0], dumpLines: <string[]><any>values[1][1] };
        });
    }
    private variablesRefId: number;
    private isComplexObject(value: string): boolean {
        value = value.trim();
        return (value.indexOf("{") === 0 && value.lastIndexOf("}") === value.length - 1) ||
            (value.indexOf("instance of ") === 0 && value.indexOf("]") > value.indexOf("["));
    }
    private isArray(printValue: string, value: string): boolean {
        if ((value.indexOf("{") === 0 && value.lastIndexOf("}") === value.length - 1) &&
            (printValue.trim().indexOf("instance of ") === 0 && printValue.indexOf("]") > printValue.indexOf("["))) {
            return printValue.substring(printValue.lastIndexOf("]") + 1).trim().indexOf("(") == 0;
        }

        return false;
    }
    private addScopeAndVariables(scopes: DebugProtocol.Scope[], scopeName: string, values: string[]): Promise<any> {
        let variables: IDebugVariable = { evaluateChildren: false, variables: [] };
        var promises = values.map(argAndValue => {
            if (argAndValue.indexOf(" = ") === -1) {
                return Promise.resolve();
            }

            var variableName = argAndValue.substring(0, argAndValue.indexOf("=")).trim();
            return this.getVariableValue(variableName).then(value => {
                var isComplex = this.isComplexObject(value.printedValue) || this.isComplexObject(value.dumpValue);
                variables.variables.push({
                    StringRepr: value.printedValue,
                    ChildName: "",
                    ExceptionText: "",
                    Expression: variableName,
                    Flags: isComplex ? JavaEvaluationResultFlags.Expandable : JavaEvaluationResultFlags.Raw,
                    Frame: null,
                    IsExpandable: isComplex,
                    Length: 0,
                    TypeName: "string",
                    DumpRepr: value.dumpValue,
                    DumpLines: value.dumpLines
                });
            }).catch(() => {
                //swallow exception
                return "";
            });
        });

        return Promise.all(promises).then(() => {
            scopes.push(new Scope(scopeName, this._variableHandles.create(variables), false));
        });
    }
    protected scopesRequest(response: DebugProtocol.ScopesResponse, args: DebugProtocol.ScopesArguments): void {
        this.jdbRunner.jdbLoaded.then(() => {
            var scopes: DebugProtocol.Scope[] = [];
            response.body = { scopes };
            this.jdbRunner.sendCmd("locals").then(data => {
                if (data.length === 0 || data.length === 1) {
                    this.sendResponse(response);
                    return;
                }

                //Parse the variables
                var startIndexOfMethodArgs = -1;
                var startIndexOfLocalVariables = -1;
                data.forEach((line, index)=>{
                    if (startIndexOfMethodArgs === -1 && line.indexOf("Method arguments:") === 0) {
                        startIndexOfMethodArgs = index;
                    }
                    if (startIndexOfLocalVariables === -1 && line.indexOf("Local variables:") === 0) {
                        startIndexOfLocalVariables = index;
                    }
                });
 
                var argsPromise = Promise.resolve();
                if (startIndexOfMethodArgs >= 0) {
                    var args = data.filter((line, index) => index >= startIndexOfMethodArgs && index < startIndexOfLocalVariables);
                    argsPromise = this.addScopeAndVariables(scopes, "Arguments", args);
                }

                var varsPromise = Promise.resolve();
                if (startIndexOfLocalVariables >= 0) {
                    var args = data.filter((line, index) => index >= startIndexOfLocalVariables);
                    varsPromise = this.addScopeAndVariables(scopes, "Locals", args);
                }

                Promise.all([argsPromise, varsPromise]).then(() => this.sendResponse(response));
            });
        });
    }

    private lastRequestedVariableId: string;
    protected variablesRequest(response: DebugProtocol.VariablesResponse, args: DebugProtocol.VariablesArguments): void {
        var varRef = this._variableHandles.get(args.variablesReference);

        if (varRef.evaluateChildren === true) {
            var parentVariable = varRef.variables[0];
            if (this.isArray(parentVariable.StringRepr, parentVariable.DumpRepr)) {
                //Split by commas
                var value = parentVariable.DumpRepr.trim().substring(1);
                value = value.substring(0, value.length - 1);
                let variables = [];
                value.split(", ").forEach((item, index) => {
                    var variable = <IJavaEvaluationResult>{
                        StringRepr: item,
                        ChildName: `[${index}]`,
                        ExceptionText: "",
                        Expression: `${parentVariable.Expression}[${index}]`,
                        Flags: this.isComplexObject(item) ? JavaEvaluationResultFlags.Expandable : JavaEvaluationResultFlags.Raw,
                        Frame: null,
                        IsExpandable: this.isComplexObject(item),
                        Length: 0,
                        TypeName: "string",
                        DumpRepr: item,
                        DumpLines: []
                    };
                    let variablesReference = 0;
                    //If this value can be expanded, then create a vars ref for user to expand it
                    if (variable.IsExpandable) {
                        const parentVariable: IDebugVariable = {
                            variables: [variable],
                            evaluateChildren: true
                        };
                        variablesReference = this._variableHandles.create(parentVariable);
                    }

                    variables.push({
                        name: variable.ChildName,
                        value: variable.StringRepr,
                        variablesReference: variablesReference
                    });
                });
                response.body = {
                    variables: variables
                };
                this.sendResponse(response);
            }
            else {
                if (this.isComplexObject(parentVariable.DumpRepr) && parentVariable.StringRepr.indexOf("@") > 0) {
                    let variables = [];
                    var promises = parentVariable.DumpLines.map(propertyLine => {
                        if (propertyLine.trim().length === 1) {
                            return Promise.resolve();
                        }
                        var propertyName = propertyLine.substring(0, propertyLine.indexOf(":")).trim();
                        propertyName = propertyName.substring(propertyName.lastIndexOf(".") + 1).trim();
                        var value = propertyLine.substring(propertyLine.indexOf(":") + 2);

                        var expr = `${parentVariable.Expression}.${propertyName}`;
                        return this.getVariableValue(expr).then(values => {
                            var isComplex = this.isComplexObject(values.printedValue) || this.isComplexObject(values.dumpValue);
                            var variable = <IJavaEvaluationResult>{
                                StringRepr: values.printedValue,
                                ChildName: propertyName,
                                ExceptionText: "",
                                Expression: expr,
                                Flags: isComplex ? JavaEvaluationResultFlags.Expandable : JavaEvaluationResultFlags.Raw,
                                Frame: null,
                                IsExpandable: isComplex,
                                Length: 0,
                                TypeName: "string",
                                DumpRepr: values.dumpValue,
                                DumpLines: values.dumpLines
                            };
                            let variablesReference = 0;
                            //If this value can be expanded, then create a vars ref for user to expand it
                            if (variable.IsExpandable) {
                                const parentVariable: IDebugVariable = {
                                    variables: [variable],
                                    evaluateChildren: true
                                };
                                variablesReference = this._variableHandles.create(parentVariable);
                            }

                            variables.push({
                                name: variable.ChildName,
                                value: variable.StringRepr,
                                variablesReference: variablesReference
                            });
                        }).catch(() => {
                            return "";
                        });
                    });

                    Promise.all(promises).then(() => {
                        response.body = {
                            variables: variables
                        };
                        this.sendResponse(response);
                    });

                    return;
                }

                response.body = {
                    variables: []
                };
                this.sendResponse(response);
            }
        }
        else {
            let variables = [];
            varRef.variables.forEach(variable => {
                let variablesReference = 0;
                //If this value can be expanded, then create a vars ref for user to expand it
                if (variable.IsExpandable) {
                    const parentVariable: IDebugVariable = {
                        variables: [variable],
                        evaluateChildren: true
                    };
                    variablesReference = this._variableHandles.create(parentVariable);
                }

                variables.push({
                    name: variable.Expression,
                    value: variable.StringRepr,
                    variablesReference: variablesReference
                });
            });

            response.body = {
                variables: variables
            };

            return this.sendResponse(response);
        }
    }

    protected stepInRequest(response: DebugProtocol.StepInResponse): void {
        if (!this.jdbRunner.readyToAcceptCommands) {
            return
        }
        this.sendResponse(response);
        this.jdbRunner.sendCmd("step").then((data) => {
            this.sendEvent(new StoppedEvent("step", MAIN_THREAD_ID));
            this.handleGenericResponse(data);
        });
    }

    protected stepOutRequest(response: DebugProtocol.StepInResponse): void {
        this.sendResponse(response);
        this.jdbRunner.sendCmd("step up").then((data) => {
            this.sendEvent(new StoppedEvent("step out", MAIN_THREAD_ID));
            this.handleGenericResponse(data);
        });
    }

    private handleGenericResponse(data: string[]) {
        //Handle responses like exiting out of the system
        //If the first item starts with >, then this is most likely an output
        if (data.length === 0) {
            return;
        }
        var responseStartsFromIndex = 0;
        if (data[0].indexOf("> ") === 0) {
            var outputList: string[] = [];
            var outputEnded = false;
            data.forEach((line, index) => {
                if (outputEnded) {
                    return;
                }
                if (line.indexOf("Step completed:") === 0 || line.indexOf("Breakpoint hit:") === 0) {
                    responseStartsFromIndex = index;
                    outputEnded = true;
                    return;
                }
                if (index === 0) {
                    outputList.push(line.substring(2));
                }
                else {
                    outputList.push(line);
                }
            });

            var dataToSend = "";
            //If we have an output, then we'd have at least one line
            if (outputList.length === 1 && outputList[0].length === 0) {
                dataToSend = outputList[0];
            }
            else {
                //Add empty entry for a blank line (after the last message)
                //Unfortunately this will result in cases where we have linebreaks unnecessarily
                outputList.push("");
                dataToSend = outputList.join("\n")
            }
            this.sendEvent(new OutputEvent(dataToSend));
            data = data.filter((v, index) => index >= responseStartsFromIndex);
        }

        //Check if we have hit a breakpoint
        //Breakpoint hit:
        if (data.some(line => line.indexOf("Breakpoint hit:") === 0)) {
            this.sendEvent(new StoppedEvent("breakpoint", MAIN_THREAD_ID));
        }
    }
 
    protected disconnectRequest(response: DebugProtocol.DisconnectResponse, args: DebugProtocol.DisconnectArguments): void {
        this.sendResponse(response);
        this.jdbRunner.sendCmd("exit").then((data) => {
            this.handleGenericResponse(data);
        });
    }

    protected continueRequest(response: DebugProtocol.ContinueResponse, args: DebugProtocol.ContinueArguments): void {
        this.sendResponse(response);
        this.jdbRunner.sendCmd("cont").then((data) => {
            this.handleGenericResponse(data);
        });
    }

    protected nextRequest(response: DebugProtocol.NextResponse, args: DebugProtocol.NextArguments): void {
        this.sendResponse(response);
        this.jdbRunner.sendCmd("next").then((data) => {
            this.sendEvent(new StoppedEvent("next", MAIN_THREAD_ID));
            this.handleGenericResponse(data);
        });
    }

    protected evaluateRequest(response: DebugProtocol.EvaluateResponse, args: DebugProtocol.EvaluateArguments): void {
        // this.sendCommand(`p ${args.expression}`, response).then((data) => {
        //     response.body = {
        //         result: (<string[]>data).join(),
        //         variablesReference: 0
        //     };
        //     this.sendResponse(response);
        // });
    }

    //Unsupported features
    protected pauseRequest(response: DebugProtocol.PauseResponse): void {
        // console.error('Not yet implemented: pauseRequest');
        this.sendErrorResponse(response, 2000, "Pause is not yet supported");
    }

    protected setExceptionBreakPointsRequest(response: DebugProtocol.SetExceptionBreakpointsResponse, args: DebugProtocol.SetExceptionBreakpointsArguments): void {
        // console.error('Not yet implemented: setExceptionBreakPointsRequest');
        this.sendErrorResponse(response, 2000, "ExceptionBreakPointsRequest is not yet supported");
    }

}

DebugSession.run(JavaDebugSession);
