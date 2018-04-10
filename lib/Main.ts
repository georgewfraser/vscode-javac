'use strict';
// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as VSCode from "vscode";
import * as Path from "path";
import * as FS from "fs";
import * as PortFinder from "portfinder";
import * as Net from "net";
import * as ChildProcess from "child_process";
import {LanguageClient, LanguageClientOptions, StreamInfo} from "vscode-languageclient";

/** Called when extension is activated */
export function activate(context: VSCode.ExtensionContext) {
    console.log('Activating Java');

    let javaExecutablePath = findJavaExecutable('java');
    
    if (javaExecutablePath == null) {
        VSCode.window.showErrorMessage("Couldn't locate java in $JAVA_HOME or $PATH");
        
        return;
    }
        
    isJava8(javaExecutablePath).then(eight => {
        if (!eight) {
            VSCode.window.showErrorMessage('Java language support requires Java 8 (using ' + javaExecutablePath + ')');
            
            return;
        }
                    
        // Options to control the language client
        let clientOptions: LanguageClientOptions = {
            // Register the server for java documents
            documentSelector: ['java'],
            synchronize: {
                // Synchronize the setting section 'java' to the server
                // NOTE: this currently doesn't do anything
                configurationSection: 'java',
                // Notify the server about file changes to 'javaconfig.json' files contain in the workspace
                fileEvents: [
                    VSCode.workspace.createFileSystemWatcher('**/javaconfig.json'),
                    VSCode.workspace.createFileSystemWatcher('**/*.java')
                ]
            },
            outputChannelName: 'Java',
            revealOutputChannelOn: 4 // never
        }
        
        function createServer(): Promise<StreamInfo> {
            return new Promise((resolve, reject) => {
                PortFinder.getPort({port: 55282}, (err, port) => {
                    let fatJar = Path.resolve(context.extensionPath, "out", "fat-jar.jar");
                    
                    let args = [
                        '-cp', fatJar, 
                        '-Djavacs.port=' + port,
                        '-Xverify:none', // helps VisualVM avoid 'error 62'
                        'org.javacs.Main'
                    ];
                    
                    console.log(javaExecutablePath + ' ' + args.join(' '));
                    
                    Net.createServer(socket => {
                        console.log('Child process connected on port ' + port);

                        resolve({
                            reader: socket,
                            writer: socket
                        });
                    }).listen(port, () => {
                        // Start the child java process
                        let options = { cwd: VSCode.workspace.rootPath };
                        let process = ChildProcess.spawn(javaExecutablePath, args, options);

                        // Send raw output to a file
                        if (!FS.existsSync(context.storagePath))
                            FS.mkdirSync(context.storagePath);

                        let logFile = context.storagePath + '/vscode-javac.log';
                        let logStream = FS.createWriteStream(logFile, {flags: 'w'});

                        process.stdout.pipe(logStream);
                        process.stderr.pipe(logStream);

                        console.log(`Storing log in '${logFile}'`);
                    });
                });
            });
        }

        // Copied from typescript
        VSCode.languages.setLanguageConfiguration('java', {
            indentationRules: {
                // ^(.*\*/)?\s*\}.*$
                decreaseIndentPattern: /^((?!.*?\/\*).*\*\/)?\s*[\}\]\)].*$/,
                // ^.*\{[^}"']*$
                increaseIndentPattern: /^((?!\/\/).)*(\{[^}"'`]*|\([^)"'`]*|\[[^\]"'`]*)$/,
                indentNextLinePattern: /^\s*(for|while|if|else)\b(?!.*[;{}]\s*(\/\/.*|\/[*].*[*]\/\s*)?$)/
            },
            onEnterRules: [
                {
                    // e.g. /** | */
                    beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
                    afterText: /^\s*\*\/$/,
                    action: { indentAction: VSCode.IndentAction.IndentOutdent, appendText: ' * ' }
                }, {
                    // e.g. /** ...|
                    beforeText: /^\s*\/\*\*(?!\/)([^\*]|\*(?!\/))*$/,
                    action: { indentAction: VSCode.IndentAction.None, appendText: ' * ' }
                }, {
                    // e.g.  * ...|
                    beforeText: /^(\t|(\ \ ))*\ \*(\ ([^\*]|\*(?!\/))*)?$/,
                    action: { indentAction: VSCode.IndentAction.None, appendText: '* ' }
                }, {
                    // e.g.  */|
                    beforeText: /^(\t|(\ \ ))*\ \*\/\s*$/,
                    action: { indentAction: VSCode.IndentAction.None, removeText: 1 }
                },
                {
                    // e.g.  *-----*/|
                    beforeText: /^(\t|(\ \ ))*\ \*[^/]*\*\/\s*$/,
                    action: { indentAction: VSCode.IndentAction.None, removeText: 1 }
                }
            ]
        })

        // Create the language client and start the client.
        let languageClient = new LanguageClient('vscode-javac', 'Java Language Server', createServer, clientOptions);
        let disposable = languageClient.start();

        // Push the disposable to the context's subscriptions so that the 
        // client can be deactivated on extension deactivation
        context.subscriptions.push(disposable);
    });
}

interface CustomTelemetryEvent {
    event: 'Progress'|'Done';
    file?: string;
}

function isJava8(javaExecutablePath: string): Promise<boolean> {
    return new Promise((resolve, reject) => {
        let result = ChildProcess.execFile(javaExecutablePath, ['-version'], { }, (error, stdout, stderr) => {
            let eight = stderr.indexOf('1.8') >= 0, nine = stderr.indexOf('"9"') >= 0;
            
            resolve(eight || nine);
        });
    });
}

function findJavaExecutable(binname: string) {
	binname = correctBinname(binname);

	// First search java.home setting
    let userJavaHome = VSCode.workspace.getConfiguration('java').get('home') as string;

	if (userJavaHome != null) {
        console.log('Looking for java in settings java.home ' + userJavaHome + '...');

        let candidate = findJavaExecutableInJavaHome(userJavaHome, binname);

        if (candidate != null)
            return candidate;
	}

	// Then search each JAVA_HOME
    let envJavaHome = process.env['JAVA_HOME'];

	if (envJavaHome) {
        console.log('Looking for java in environment variable JAVA_HOME ' + envJavaHome + '...');

        let candidate = findJavaExecutableInJavaHome(envJavaHome, binname);

        if (candidate != null)
            return candidate;
	}

	// Then search PATH parts
	if (process.env['PATH']) {
        console.log('Looking for java in PATH');
        
		let pathparts = process.env['PATH'].split(Path.delimiter);
		for (let i = 0; i < pathparts.length; i++) {
			let binpath = Path.join(pathparts[i], binname);
			if (FS.existsSync(binpath)) {
				return binpath;
			}
		}
	}
    
	// Else return the binary name directly (this will likely always fail downstream) 
	return null;
}

function correctBinname(binname: string) {
	if (process.platform === 'win32')
		return binname + '.exe';
	else
		return binname;
}

function findJavaExecutableInJavaHome(javaHome: string, binname: string) {
    let workspaces = javaHome.split(Path.delimiter);

    for (let i = 0; i < workspaces.length; i++) {
        let binpath = Path.join(workspaces[i], 'bin', binname);

        if (FS.existsSync(binpath)) 
            return binpath;
    }
}

// this method is called when your extension is deactivated
export function deactivate() {
}