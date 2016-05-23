import {DebugProtocol} from 'vscode-debugprotocol';

export interface LaunchRequestArguments extends DebugProtocol.LaunchRequestArguments {
    program: string;
    jdkPath?: string;
    stopOnEntry?: boolean;
    externalConsole?: boolean;
    debugOptions?: string[];
    options?: string[];
}

export interface IJavaThread {
    IsWorkerThread: boolean;
    Name: string;
    Id: number;
    Frames: IJavaStackFrame[];
}

export interface IJavaStackFrame {
    StartLine: number;
    EndLine: number;
    Thread: IJavaThread;
    LineNo: number;
    FunctionName: string;
    FileName: string;
    FrameId: number;
    Locals: IJavaEvaluationResult[];
    Parameters: IJavaEvaluationResult[];
}
export enum JavaEvaluationResultFlags {
    None = 0,
    Expandable = 1,
    MethodCall = 2,
    SideEffects = 4,
    Raw = 8,
    HasRawRepr = 16,
}

export interface IJavaEvaluationResult {
    Flags: JavaEvaluationResultFlags;
    IsExpandable: boolean;
    StringRepr: string;
    TypeName: string;
    Length: number;
    ExceptionText?: string;
    Expression: string;
    ChildName: string;
    Frame: IJavaStackFrame;
    DumpRepr: string;
    DumpLines: string[]
}

export interface IDebugVariable {
    variables: IJavaEvaluationResult[];
    evaluateChildren?: Boolean;
}

export interface ICommand {
    name: string;
    prompt?: string
    promptResponse?: string;
    commandLineDetected?: boolean;
    commandLine: string;
    responseProtocol?: DebugProtocol.Response
}

export interface IStackInfo {
    fileName: string,
    lineNumber: number,
    function: string
    source: string
}
