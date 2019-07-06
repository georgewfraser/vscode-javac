package org.javacs.debug;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import java.util.Map;

/** Base class of requests, responses, and events. */
class ProtocolMessage {
    /** Sequence number. */
    int seq;
    /** Message type. Values: 'request', 'response', 'event', etc. */
    String type;
}

/** A client or debug adapter initiated request. */
class Request extends ProtocolMessage {
    // type: 'request';
    /** The command to execute. */
    String command;
    /** Object containing arguments for the command. */
    JsonObject arguments;
}

/** A debug adapter initiated event. */
class Event extends ProtocolMessage {
    // type: 'event';
    /** Type of event. */
    String event;
    /** Event-specific information. */
    JsonObject body;
}

/** Response for a request. */
class Response extends ProtocolMessage {
    // type: 'response';
    /** Sequence number of the corresponding request. */
    int request_seq;
    /** Outcome of the request. */
    boolean success;
    /** The command requested. */
    String command;
    /** Contains error message if success == false. */
    String message;
    /** Contains request result if success is true and optional error details if success is false. */
    JsonObject body;
}

/** On error (whenever 'success' is false), the body can provide more details. */
class ErrorResponse extends Response {
    ErrorResponseBody Body;
}

class ErrorResponseBody {
    Message error;
}

/**
 * Event message for 'initialized' event type. This event indicates that the debug adapter is ready to accept
 * configuration requests (e.g. SetBreakpointsRequest, SetExceptionBreakpointsRequest). A debug adapter is expected to
 * send this event when it is ready to accept configuration requests (but not before the 'initialize' request has
 * finished). The sequence of events/requests is as follows: - adapters sends 'initialized' event (after the
 * 'initialize' request has returned) - frontend sends zero or more 'setBreakpoints' requests - frontend sends one
 * 'setFunctionBreakpoints' request - frontend sends a 'setExceptionBreakpoints' request if one or more
 * 'exceptionBreakpointFilters' have been defined (or if 'supportsConfigurationDoneRequest' is not defined or false) -
 * frontend sends other future configuration requests - frontend sends one 'configurationDone' request to indicate the
 * end of the configuration.
 */
class InitializedEvent extends Event {
    // event: 'initialized';
}

/**
 * Event message for 'stopped' event type. The event indicates that the execution of the debuggee has stopped due to
 * some condition. This can be caused by a break point previously set, a stepping action has completed, by executing a
 * debugger statement etc.
 */
class StoppedEvent extends Event {
    // event: 'stopped';
    StoppedEventBody body;
}

class StoppedEventBody {
    /**
     * The reason for the event. For backward compatibility this string is shown in the UI if the 'description'
     * attribute is missing (but it must not be translated). Values: 'step', 'breakpoint', 'exception', 'pause',
     * 'entry', 'goto', 'function breakpoint', 'data breakpoint', etc.
     */
    String reason;
    /**
     * The full reason for the event, e.g. 'Paused on exception'. This string is shown in the UI as is and must be
     * translated.
     */
    String description;
    /** The thread which was stopped. */
    Integer threadId;
    /** A value of true hints to the frontend that this event should not change the focus. */
    Boolean preserveFocusHint;
    /**
     * Additional information. E.g. if reason is 'exception', text contains the exception name. This string is shown in
     * the UI.
     */
    String text;
    /**
     * If 'allThreadsStopped' is true, a debug adapter can announce that all threads have stopped. - The client should
     * use this information to enable that all threads can be expanded to access their stacktraces. - If the attribute
     * is missing or false, only the thread with the given threadId can be expanded.
     */
    Boolean allThreadsStopped;
}

/**
 * Event message for 'continued' event type. The event indicates that the execution of the debuggee has continued.
 * Please note: a debug adapter is not expected to send this event in response to a request that implies that execution
 * continues, e.g. 'launch' or 'continue'. It is only necessary to send a 'continued' event if there was no previous
 * request that implied this.
 */
class ContinuedEvent extends Event {
    // event: 'continued';
    ContinuedEventBody body;
}

class ContinuedEventBody {
    /** The thread which was continued. */
    int threadId;
    /** If 'allThreadsContinued' is true, a debug adapter can announce that all threads have continued. */
    Boolean allThreadsContinued;
}

/**
 * Event message for 'exited' event type. The event indicates that the debuggee has exited and returns its exit code.
 */
class ExitedEvent extends Event {
    // event: 'exited';
    ExitedEventBody body;
}

class ExitedEventBody {
    /** The exit code returned from the debuggee. */
    int exitCode;
}

/**
 * Event message for 'terminated' event type. The event indicates that debugging of the debuggee has terminated. This
 * does **not** mean that the debuggee itself has exited.
 */
class TerminatedEvent extends Event {
    // event: 'terminated';
    TerminatedEventBody body;
}

class TerminatedEventBody {
    /**
     * A debug adapter may set 'restart' to true (or to an arbitrary object) to request that the front end restarts the
     * session. The value is not interpreted by the client and passed unmodified as an attribute '__restart' to the
     * 'launch' and 'attach' requests.
     */
    JsonObject restart;
}

/** Event message for 'thread' event type. The event indicates that a thread has started or exited. */
class ThreadEvent extends Event {
    // event: 'thread';
    ThreadEventBody body;
}

class ThreadEventBody {
    /** The reason for the event. Values: 'started', 'exited', etc. */
    String reason;
    /** The identifier of the thread. */
    int threadId;
}

/** Event message for 'output' event type. The event indicates that the target has produced some output. */
class OutputEvent extends Event {
    // event: 'output';
    OutputEventBody body;
}

class OutputEventBody {
    /**
     * The output category. If not specified, 'console' is assumed. Values: 'console', 'stdout', 'stderr', 'telemetry',
     * etc.
     */
    String category;
    /** The output to report. */
    String output;
    /**
     * If an attribute 'variablesReference' exists and its value is > 0, the output contains objects which can be
     * retrieved by passing 'variablesReference' to the 'variables' request.
     */
    Integer variablesReference;
    /** An optional source location where the output was produced. */
    Source source;
    /** An optional source location line where the output was produced. */
    Integer line;
    /** An optional source location column where the output was produced. */
    Integer column;
    /**
     * Optional data to report. For the 'telemetry' category the data will be sent to telemetry, for the other
     * categories the data is shown in JSON format.
     */
    JsonObject data;
}

/**
 * Event message for 'breakpoint' event type. The event indicates that some information about a breakpoint has changed.
 */
class BreakpointEvent extends Event {
    // event: 'breakpoint';
    BreakpointEventBody body;
}

class BreakpointEventBody {
    /** The reason for the event. Values: 'changed', 'new', 'removed', etc. */
    String reason;
    /** The 'id' attribute is used to find the target breakpoint and the other attributes are used as the new values. */
    Breakpoint breakpoint;
}

/** Event message for 'module' event type. The event indicates that some information about a module has changed. */
class ModuleEvent extends Event {
    // event: 'module';
    ModuleEventBody body;
}

class ModuleEventBody {
    /** The reason for the event. 'new' | 'changed' | 'removed'. */
    String reason;
    /** The new, changed, or removed module. In case of 'removed' only the module id is used. */
    Module module;
}

/**
 * Event message for 'loadedSource' event type. The event indicates that some source has been added, changed, or removed
 * from the set of all loaded sources.
 */
class LoadedSourceEvent extends Event {
    // event: 'loadedSource';
    LoadedSourceEventBody body;
}

class LoadedSourceEventBody {
    /** The reason for the event. 'new' | 'changed' | 'removed'. */
    String reason;
    /** The new, changed, or removed source. */
    Source source;
}

/**
 * Event message for 'process' event type. The event indicates that the debugger has begun debugging a new process.
 * Either one that it has launched, or one that it has attached to.
 */
class ProcessEvent extends Event {
    // event: 'process';
    ProcessEventBody body;
}

class ProcessEventBody {
    /**
     * The logical name of the process. This is usually the full path to process's executable file. Example:
     * /home/example/myproj/program.js.
     */
    String name;
    /** The system process id of the debugged process. This property will be missing for non-system processes. */
    Integer systemProcessId;
    /** If true, the process is running on the same computer as the debug adapter. */
    Boolean isLocalProcess;
    /**
     * Describes how the debug engine started debugging this process. 'launch': Process was launched under the debugger.
     * 'attach': Debugger attached to an existing process. 'attachForSuspendedLaunch': A project launcher component has
     * launched a new process in a suspended state and then asked the debugger to attach.
     */
    String startMethod;
    /**
     * The size of a pointer or address for this process, in bits. This value may be used by clients when formatting
     * addresses for display.
     */
    Integer pointerSize;
}

/**
 * Event message for 'capabilities' event type. The event indicates that one or more capabilities have changed. Since
 * the capabilities are dependent on the frontend and its UI, it might not be possible to change that at random times
 * (or too late). Consequently this event has a hint characteristic: a frontend can only be expected to make a 'best
 * effort' in honouring individual capabilities but there are no guarantees. Only changed capabilities need to be
 * included, all other capabilities keep their values.
 */
class CapabilitiesEvent extends Event {
    // event: 'capabilities';
    CapabilitiesEventBody body;
}

class CapabilitiesEventBody {
    /** The set of updated capabilities. */
    Capabilities capabilities;
}

/**
 * RunInTerminal request; value of command field is 'runInTerminal'. This request is sent from the debug adapter to the
 * client to run a command in a terminal. This is typically used to launch the debuggee in a terminal provided by the
 * client.
 */
class RunInTerminalRequest extends Request {
    // command: 'runInTerminal';
    RunInTerminalRequestArguments arguments;
}

/** Arguments for 'runInTerminal' request. */
class RunInTerminalRequestArguments {
    /** What kind of terminal to launch. 'integrated' | 'external'. */
    String kind;
    /** Optional title of the terminal. */
    String title;
    /** Working directory of the command. */
    String cwd;
    /** List of arguments. The first argument is the command to run. */
    String[] args;
    /** Environment key-value pairs that are added to or removed from the default environment. */
    Map<String, String> env;
}

/** Response to 'runInTerminal' request. */
class RunInTerminalResponse extends Response {
    RunInTerminalResponseBody body;
}

class RunInTerminalResponseBody {
    /** The process ID. */
    Integer processId;
    /** The process ID of the terminal shell. */
    Integer shellProcessId;
}

/**
 * Initialize request; value of command field is 'initialize'. The 'initialize' request is sent as the first request
 * from the client to the debug adapter in order to configure it with client capabilities and to retrieve capabilities
 * from the debug adapter. Until the debug adapter has responded to with an 'initialize' response, the client must not
 * send any additional requests or events to the debug adapter. In addition the debug adapter is not allowed to send any
 * requests or events to the client until it has responded with an 'initialize' response. The 'initialize' request may
 * only be sent once.
 */
class InitializeRequest extends Request {
    // command: 'initialize';
    InitializeRequestArguments arguments;
}

/** Arguments for 'initialize' request. */
class InitializeRequestArguments {
    /** The ID of the (frontend) client using this adapter. */
    String clientID;
    /** The human readable name of the (frontend) client using this adapter. */
    String clientName;
    /** The ID of the debug adapter. */
    String adapterID;
    /** The ISO-639 locale of the (frontend) client using this adapter, e.g. en-US or de-CH. */
    String locale;
    /** If true all line numbers are 1-based (default). */
    Boolean linesStartAt1;
    /** If true all column numbers are 1-based (default). */
    Boolean columnsStartAt1;
    /**
     * Determines in what format paths are specified. The default is 'path', which is the native format. Values: 'path',
     * 'uri', etc.
     */
    String pathFormat;
    /** Client supports the optional type attribute for variables. */
    Boolean supportsVariableType;
    /** Client supports the paging of variables. */
    Boolean supportsVariablePaging;
    /** Client supports the runInTerminal request. */
    Boolean supportsRunInTerminalRequest;
    /** Client supports memory references. */
    Boolean supportsMemoryReferences;
}

/** Response to 'initialize' request. */
class InitializeResponse extends Response {
    /** The capabilities of this debug adapter. */
    Capabilities body;
}

/**
 * ConfigurationDone request; value of command field is 'configurationDone'. The client of the debug protocol must send
 * this request at the end of the sequence of configuration requests (which was started by the 'initialized' event).
 */
class ConfigurationDoneRequest extends Request {
    // command: 'configurationDone';
    ConfigurationDoneArguments arguments;
}

/** Arguments for 'configurationDone' request. */
class ConfigurationDoneArguments {}

/** Response to 'configurationDone' request. This is just an acknowledgement, so no body field is required. */
class ConfigurationDoneResponse extends Response {}

/**
 * Launch request; value of command field is 'launch'. The launch request is sent from the client to the debug adapter
 * to start the debuggee with or without debugging (if 'noDebug' is true). Since launching is debugger/runtime specific,
 * the arguments for this request are not part of this specification.
 */
class LaunchRequest extends Request {
    // command: 'launch';
    LaunchRequestArguments arguments;
}

/** Arguments for 'launch' request. Additional attributes are implementation specific. */
class LaunchRequestArguments {
    /** If noDebug is true the launch request should launch the program without enabling debugging. */
    Boolean noDebug;
    /**
     * Optional data from the previous, restarted session. The data is sent as the 'restart' attribute of the
     * 'terminated' event. The client should leave the data intact.
     */
    JsonObject __restart;
}

/** Response to 'launch' request. This is just an acknowledgement, so no body field is required. */
class LaunchResponse extends Response {}

/**
 * Attach request; value of command field is 'attach'. The attach request is sent from the client to the debug adapter
 * to attach to a debuggee that is already running. Since attaching is debugger/runtime specific, the arguments for this
 * request are not part of this specification.
 */
class AttachRequest extends Request {
    // command: 'attach';
    AttachRequestArguments arguments;
}

/** Arguments for 'attach' request. Additional attributes are implementation specific. */
class AttachRequestArguments {
    /**
     * Optional data from the previous, restarted session. The data is sent as the 'restart' attribute of the
     * 'terminated' event. The client should leave the data intact.
     */
    JsonObject __restart;
}

/** Response to 'attach' request. This is just an acknowledgement, so no body field is required. */
class AttachResponse extends Response {}

/**
 * Restart request; value of command field is 'restart'. Restarts a debug session. If the capability
 * 'supportsRestartRequest' is missing or has the value false, the client will implement 'restart' by terminating the
 * debug adapter first and then launching it anew. A debug adapter can override this default behaviour by implementing a
 * restart request and setting the capability 'supportsRestartRequest' to true.
 */
class RestartRequest extends Request {
    // command: 'restart';
    RestartArguments arguments;
}

/** Arguments for 'restart' request. */
class RestartArguments {}

/** Response to 'restart' request. This is just an acknowledgement, so no body field is required. */
class RestartResponse extends Response {}

/**
 * Disconnect request; value of command field is 'disconnect'. The 'disconnect' request is sent from the client to the
 * debug adapter in order to stop debugging. It asks the debug adapter to disconnect from the debuggee and to terminate
 * the debug adapter. If the debuggee has been started with the 'launch' request, the 'disconnect' request terminates
 * the debuggee. If the 'attach' request was used to connect to the debuggee, 'disconnect' does not terminate the
 * debuggee. This behavior can be controlled with the 'terminateDebuggee' argument (if supported by the debug adapter).
 */
class DisconnectRequest extends Request {
    // command: 'disconnect';
    DisconnectArguments arguments;
}

/** Arguments for 'disconnect' request. */
class DisconnectArguments {
    /** A value of true indicates that this 'disconnect' request is part of a restart sequence. */
    Boolean restart;
    /**
     * Indicates whether the debuggee should be terminated when the debugger is disconnected. If unspecified, the debug
     * adapter is free to do whatever it thinks is best. A client can only rely on this attribute being properly honored
     * if a debug adapter returns true for the 'supportTerminateDebuggee' capability.
     */
    Boolean terminateDebuggee;
}

/** Response to 'disconnect' request. This is just an acknowledgement, so no body field is required. */
class DisconnectResponse extends Response {}

/**
 * Terminate request; value of command field is 'terminate'. The 'terminate' request is sent from the client to the
 * debug adapter in order to give the debuggee a chance for terminating itself.
 */
class TerminateRequest extends Request {
    // command: 'terminate';
    TerminateArguments arguments;
}

/** Arguments for 'terminate' request. */
class TerminateArguments {
    /** A value of true indicates that this 'terminate' request is part of a restart sequence. */
    Boolean restart;
}

/** Response to 'terminate' request. This is just an acknowledgement, so no body field is required. */
class TerminateResponse extends Response {}

/**
 * SetBreakpoints request; value of command field is 'setBreakpoints'. Sets multiple breakpoints for a single source and
 * clears all previous breakpoints in that source. To clear all breakpoint for a source, specify an empty array. When a
 * breakpoint is hit, a 'stopped' event (with reason 'breakpoint') is generated.
 */
class SetBreakpointsRequest extends Request {
    // command: 'setBreakpoints';
    SetBreakpointsArguments arguments;
}

/** Arguments for 'setBreakpoints' request. */
class SetBreakpointsArguments {
    /** The source location of the breakpoints; either 'source.path' or 'source.reference' must be specified. */
    Source source;
    /** The code locations of the breakpoints. */
    SourceBreakpoint[] breakpoints;
    /** Deprecated: The code locations of the breakpoints. */
    Integer lines[];
    /**
     * A value of true indicates that the underlying source has been modified which results in new breakpoint locations.
     */
    Boolean sourceModified;
}

/**
 * Response to 'setBreakpoints' request. Returned is information about each breakpoint created by this request. This
 * includes the actual code location and whether the breakpoint could be verified. The breakpoints returned are in the
 * same order as the elements of the 'breakpoints' (or the deprecated 'lines') array in the arguments.
 */
class SetBreakpointsResponse extends Response {
    SetBreakpointsResponseBody body;
}

class SetBreakpointsResponseBody {
    /**
     * Information about the breakpoints. The array elements are in the same order as the elements of the 'breakpoints'
     * (or the deprecated 'lines') array in the arguments.
     */
    Breakpoint[] breakpoints;
}

/**
 * SetFunctionBreakpoints request; value of command field is 'setFunctionBreakpoints'. Replaces all existing function
 * breakpoints with new function breakpoints. To clear all function breakpoints, specify an empty array. When a function
 * breakpoint is hit, a 'stopped' event (with reason 'function breakpoint') is generated.
 */
class SetFunctionBreakpointsRequest extends Request {
    // command: 'setFunctionBreakpoints';
    SetFunctionBreakpointsArguments arguments;
}

/** Arguments for 'setFunctionBreakpoints' request. */
class SetFunctionBreakpointsArguments {
    /** The function names of the breakpoints. */
    FunctionBreakpoint[] breakpoints;
}

/**
 * Response to 'setFunctionBreakpoints' request. Returned is information about each breakpoint created by this request.
 */
class SetFunctionBreakpointsResponse extends Response {
    SetFunctionBreakpointsResponseBody body;
}

class SetFunctionBreakpointsResponseBody {
    /** Information about the breakpoints. The array elements correspond to the elements of the 'breakpoints' array. */
    Breakpoint[] breakpoints;
}

/**
 * SetExceptionBreakpoints request; value of command field is 'setExceptionBreakpoints'. The request configures the
 * debuggers response to thrown exceptions. If an exception is configured to break, a 'stopped' event is fired (with
 * reason 'exception').
 */
class SetExceptionBreakpointsRequest extends Request {
    // command: 'setExceptionBreakpoints';
    SetExceptionBreakpointsArguments arguments;
}

/** Arguments for 'setExceptionBreakpoints' request. */
class SetExceptionBreakpointsArguments {
    /** IDs of checked exception options. The set of IDs is returned via the 'exceptionBreakpointFilters' capability. */
    String[] filters;
    /** Configuration options for selected exceptions. */
    ExceptionOptions[] exceptionOptions;
}

/** Response to 'setExceptionBreakpoints' request. This is just an acknowledgement, so no body field is required. */
class SetExceptionBreakpointsResponse extends Response {}

/**
 * DataBreakpointInfo request; value of command field is 'dataBreakpointInfo'. Obtains information on a possible data
 * breakpoint that could be set on an expression or variable.
 */
class DataBreakpointInfoRequest extends Request {
    // command: 'dataBreakpointInfo';
    DataBreakpointInfoArguments arguments;
}

/** Arguments for 'dataBreakpointInfo' request. */
class DataBreakpointInfoArguments {
    /** Reference to the Variable container if the data breakpoint is requested for a child of the container. */
    Integer variablesReference;
    /**
     * The name of the Variable's child to obtain data breakpoint information for. If variableReference isn’t provided,
     * this can be an expression.
     */
    String name;
}

/** Response to 'dataBreakpointInfo' request. */
class DataBreakpointInfoResponse extends Response {
    DataBreakpointInfoResponseBody body;
}

class DataBreakpointInfoResponseBody {
    /**
     * An identifier for the data on which a data breakpoint can be registered with the setDataBreakpoints request or
     * null if no data breakpoint is available.
     */
    String dataId;
    /** UI string that describes on what data the breakpoint is set on or why a data breakpoint is not available. */
    String description;
    /**
     * Optional attribute listing the available access types for a potential data breakpoint. A UI frontend could
     * surface this information. 'read' | 'write' | 'readWrite'.
     */
    String[] accessTypes;
    /** Optional attribute indicating that a potential data breakpoint could be persisted across sessions. */
    Boolean canPersist;
}

/**
 * SetDataBreakpoints request; value of command field is 'setDataBreakpoints'. Replaces all existing data breakpoints
 * with new data breakpoints. To clear all data breakpoints, specify an empty array. When a data breakpoint is hit, a
 * 'stopped' event (with reason 'data breakpoint') is generated.
 */
class SetDataBreakpointsRequest extends Request {
    // command: 'setDataBreakpoints';
    SetDataBreakpointsArguments arguments;
}

/** Arguments for 'setDataBreakpoints' request. */
class SetDataBreakpointsArguments {
    /**
     * The contents of this array replaces all existing data breakpoints. An empty array clears all data breakpoints.
     */
    DataBreakpoint[] breakpoints;
}

/** Response to 'setDataBreakpoints' request. Returned is information about each breakpoint created by this request. */
class SetDataBreakpointsResponse extends Response {
    SetDataBreakpointsResponseBody body;
}

class SetDataBreakpointsResponseBody {
    /**
     * Information about the data breakpoints. The array elements correspond to the elements of the input argument
     * 'breakpoints' array.
     */
    Breakpoint[] breakpoints;
}

/** Continue request; value of command field is 'continue'. The request starts the debuggee to run again. */
class ContinueRequest extends Request {
    // command: 'continue';
    ContinueArguments arguments;
}

/** Arguments for 'continue' request. */
class ContinueArguments {
    /**
     * Continue execution for the specified thread (if possible). If the backend cannot continue on a single thread but
     * will continue on all threads, it should set the 'allThreadsContinued' attribute in the response to true.
     */
    int threadId;
}

/** Response to 'continue' request. */
class ContinueResponse extends Response {
    ContinueResponseBody body;
}

class ContinueResponseBody {
    /**
     * If true, the 'continue' request has ignored the specified thread and continued all threads instead. If this
     * attribute is missing a value of 'true' is assumed for backward compatibility.
     */
    Boolean allThreadsContinued;
}

/**
 * Next request; value of command field is 'next'. The request starts the debuggee to run again for one step. The debug
 * adapter first sends the response and then a 'stopped' event (with reason 'step') after the step has completed.
 */
class NextRequest extends Request {
    // command: 'next';
    NextArguments arguments;
}

/** Arguments for 'next' request. */
class NextArguments {
    /** Execute 'next' for this thread. */
    int threadId;
}

/** Response to 'next' request. This is just an acknowledgement, so no body field is required. */
class NextResponse extends Response {}

/**
 * StepIn request; value of command field is 'stepIn'. The request starts the debuggee to step into a function/method if
 * possible. If it cannot step into a target, 'stepIn' behaves like 'next'. The debug adapter first sends the response
 * and then a 'stopped' event (with reason 'step') after the step has completed. If there are multiple function/method
 * calls (or other targets) on the source line, the optional argument 'targetId' can be used to control into which
 * target the 'stepIn' should occur. The list of possible targets for a given source line can be retrieved via the
 * 'stepInTargets' request.
 */
class StepInRequest extends Request {
    // command: 'stepIn';
    StepInArguments arguments;
}

/** Arguments for 'stepIn' request. */
class StepInArguments {
    /** Execute 'stepIn' for this thread. */
    int threadId;
    /** Optional id of the target to step into. */
    Integer targetId;
}

/** Response to 'stepIn' request. This is just an acknowledgement, so no body field is required. */
class StepInResponse extends Response {}

/**
 * StepOut request; value of command field is 'stepOut'. The request starts the debuggee to run again for one step. The
 * debug adapter first sends the response and then a 'stopped' event (with reason 'step') after the step has completed.
 */
class StepOutRequest extends Request {
    // command: 'stepOut';
    StepOutArguments arguments;
}

/** Arguments for 'stepOut' request. */
class StepOutArguments {
    /** Execute 'stepOut' for this thread. */
    int threadId;
}

/** Response to 'stepOut' request. This is just an acknowledgement, so no body field is required. */
class StepOutResponse extends Response {}

/**
 * StepBack request; value of command field is 'stepBack'. The request starts the debuggee to run one step backwards.
 * The debug adapter first sends the response and then a 'stopped' event (with reason 'step') after the step has
 * completed. Clients should only call this request if the capability 'supportsStepBack' is true.
 */
class StepBackRequest extends Request {
    // command: 'stepBack';
    StepBackArguments arguments;
}

/** Arguments for 'stepBack' request. */
class StepBackArguments {
    /** Execute 'stepBack' for this thread. */
    int threadId;
}

/** Response to 'stepBack' request. This is just an acknowledgement, so no body field is required. */
class StepBackResponse extends Response {}

/**
 * ReverseContinue request; value of command field is 'reverseContinue'. The request starts the debuggee to run
 * backward. Clients should only call this request if the capability 'supportsStepBack' is true.
 */
class ReverseContinueRequest extends Request {
    // command: 'reverseContinue';
    ReverseContinueArguments arguments;
}

/** Arguments for 'reverseContinue' request. */
class ReverseContinueArguments {
    /** Execute 'reverseContinue' for this thread. */
    int threadId;
}

/** Response to 'reverseContinue' request. This is just an acknowledgement, so no body field is required. */
class ReverseContinueResponse extends Response {}

/**
 * RestartFrame request; value of command field is 'restartFrame'. The request restarts execution of the specified
 * stackframe. The debug adapter first sends the response and then a 'stopped' event (with reason 'restart') after the
 * restart has completed.
 */
class RestartFrameRequest extends Request {
    // command: 'restartFrame';
    RestartFrameArguments arguments;
}

/** Arguments for 'restartFrame' request. */
class RestartFrameArguments {
    /** Restart this stackframe. */
    int frameId;
}

/** Response to 'restartFrame' request. This is just an acknowledgement, so no body field is required. */
class RestartFrameResponse extends Response {}

/**
 * Goto request; value of command field is 'goto'. The request sets the location where the debuggee will continue to
 * run. This makes it possible to skip the execution of code or to executed code again. The code between the current
 * location and the goto target is not executed but skipped. The debug adapter first sends the response and then a
 * 'stopped' event with reason 'goto'.
 */
class GotoRequest extends Request {
    // command: 'goto';
    GotoArguments arguments;
}

/** Arguments for 'goto' request. */
class GotoArguments {
    /** Set the goto target for this thread. */
    int threadId;
    /** The location where the debuggee will continue to run. */
    int targetId;
}

/** Response to 'goto' request. This is just an acknowledgement, so no body field is required. */
class GotoResponse extends Response {}

/**
 * Pause request; value of command field is 'pause'. The request suspenses the debuggee. The debug adapter first sends
 * the response and then a 'stopped' event (with reason 'pause') after the thread has been paused successfully.
 */
class PauseRequest extends Request {
    // command: 'pause';
    PauseArguments arguments;
}

/** Arguments for 'pause' request. */
class PauseArguments {
    /** Pause execution for this thread. */
    int threadId;
}

/** Response to 'pause' request. This is just an acknowledgement, so no body field is required. */
class PauseResponse extends Response {}

/**
 * StackTrace request; value of command field is 'stackTrace'. The request returns a stacktrace from the current
 * execution state.
 */
class StackTraceRequest extends Request {
    // command: 'stackTrace';
    StackTraceArguments arguments;
}

/** Arguments for 'stackTrace' request. */
class StackTraceArguments {
    /** Retrieve the stacktrace for this thread. */
    int threadId;
    /** The index of the first frame to return; if omitted frames start at 0. */
    Integer startFrame;
    /** The maximum number of frames to return. If levels is not specified or 0, all frames are returned. */
    Integer levels;
    /** Specifies details on how to format the stack frames. */
    StackFrameFormat format;
}

/** Response to 'stackTrace' request. */
class StackTraceResponse extends Response {
    StackTraceResponseBody body;
}

class StackTraceResponseBody {
    /**
     * The frames of the stackframe. If the array has length zero, there are no stackframes available. This means that
     * there is no location information available.
     */
    StackFrame[] stackFrames;
    /** The total number of frames available. */
    Integer totalFrames;
}

/**
 * Scopes request; value of command field is 'scopes'. The request returns the variable scopes for a given stackframe
 * ID.
 */
class ScopesRequest extends Request {
    // command: 'scopes';
    ScopesArguments arguments;
}

/** Arguments for 'scopes' request. */
class ScopesArguments {
    /** Retrieve the scopes for this stackframe. */
    int frameId;
}

/** Response to 'scopes' request. */
class ScopesResponse extends Response {
    ScopesResponseBody body;
}

class ScopesResponseBody {
    /** The scopes of the stackframe. If the array has length zero, there are no scopes available. */
    Scope[] scopes;
}

/**
 * Variables request; value of command field is 'variables'. Retrieves all child variables for the given variable
 * reference. An optional filter can be used to limit the fetched children to either named or indexed children.
 */
class VariablesRequest extends Request {
    // command: 'variables';
    VariablesArguments arguments;
}

/** Arguments for 'variables' request. */
class VariablesArguments {
    /** The Variable reference. */
    int variablesReference;
    /**
     * Optional filter to limit the child variables to either named or indexed. If ommited, both types are fetched.
     * 'indexed' | 'named'.
     */
    String filter;
    /** The index of the first variable to return; if omitted children start at 0. */
    Integer start;
    /** The number of variables to return. If count is missing or 0, all variables are returned. */
    Integer count;
    /** Specifies details on how to format the Variable values. */
    ValueFormat format;
}

/** Response to 'variables' request. */
class VariablesResponse extends Response {
    VariablesResponseBody body;
}

class VariablesResponseBody {
    /** All (or a range) of variables for the given variable reference. */
    Variable[] variables;
}

/**
 * SetVariable request; value of command field is 'setVariable'. Set the variable with the given name in the variable
 * container to a new value.
 */
class SetVariableRequest extends Request {
    // command: 'setVariable';
    SetVariableArguments arguments;
}

/** Arguments for 'setVariable' request. */
class SetVariableArguments {
    /** The reference of the variable container. */
    int variablesReference;
    /** The name of the variable in the container. */
    String name;
    /** The value of the variable. */
    String value;
    /** Specifies details on how to format the response value. */
    ValueFormat format;
}

/** Response to 'setVariable' request. */
class SetVariableResponse extends Response {
    SetVariableResponseBody body;
}

class SetVariableResponseBody {
    /** The new value of the variable. */
    String value;
    /** The type of the new value. Typically shown in the UI when hovering over the value. */
    String type;
    /**
     * If variablesReference is > 0, the new value is structured and its children can be retrieved by passing
     * variablesReference to the VariablesRequest.
     */
    Integer variablesReference;
    /**
     * The number of named child variables. The client can use this optional information to present the variables in a
     * paged UI and fetch them in chunks.
     */
    Integer namedVariables;
    /**
     * The number of indexed child variables. The client can use this optional information to present the variables in a
     * paged UI and fetch them in chunks.
     */
    Integer indexedVariables;
}

/**
 * Source request; value of command field is 'source'. The request retrieves the source code for a given source
 * reference.
 */
class SourceRequest extends Request {
    // command: 'source';
    SourceArguments arguments;
}

/** Arguments for 'source' request. */
class SourceArguments {
    /** Specifies the source content to load. Either source.path or source.sourceReference must be specified. */
    Source source;
    /**
     * The reference to the source. This is the same as source.sourceReference. This is provided for backward
     * compatibility since old backends do not understand the 'source' attribute.
     */
    int sourceReference;
}

/** Response to 'source' request. */
class SourceResponse extends Response {
    SourceResponseBody body;
}

class SourceResponseBody {
    /** Content of the source reference. */
    String content;
    /** Optional content type (mime type) of the source. */
    String mimeType;
}

/** Threads request; value of command field is 'threads'. The request retrieves a list of all threads. */
class ThreadsRequest extends Request {
    // command: 'threads';
}

/** Response to 'threads' request. */
class ThreadsResponse extends Response {
    ThreadsResponseBody body;
}

class ThreadsResponseBody {
    /** All threads. */
    Thread[] threads;
}

/**
 * TerminateThreads request; value of command field is 'terminateThreads'. The request terminates the threads with the
 * given ids.
 */
class TerminateThreadsRequest extends Request {
    // command: 'terminateThreads';
    TerminateThreadsArguments arguments;
}

/** Arguments for 'terminateThreads' request. */
class TerminateThreadsArguments {
    /** Ids of threads to be terminated. */
    Integer threadIds[];
}

/** Response to 'terminateThreads' request. This is just an acknowledgement, so no body field is required. */
class TerminateThreadsResponse extends Response {}

/**
 * Modules request; value of command field is 'modules'. Modules can be retrieved from the debug adapter with the
 * ModulesRequest which can either return all modules or a range of modules to support paging.
 */
class ModulesRequest extends Request {
    // command: 'modules';
    ModulesArguments arguments;
}

/** Arguments for 'modules' request. */
class ModulesArguments {
    /** The index of the first module to return; if omitted modules start at 0. */
    Integer startModule;
    /** The number of modules to return. If moduleCount is not specified or 0, all modules are returned. */
    Integer moduleCount;
}

/** Response to 'modules' request. */
class ModulesResponse extends Response {
    ModulesResponseBody body;
}

class ModulesResponseBody {
    /** All modules or range of modules. */
    Module[] modules;
    /** The total number of modules available. */
    Integer totalModules;
}

/**
 * LoadedSources request; value of command field is 'loadedSources'. Retrieves the set of all sources currently loaded
 * by the debugged process.
 */
class LoadedSourcesRequest extends Request {
    // command: 'loadedSources';
    LoadedSourcesArguments arguments;
}

/** Arguments for 'loadedSources' request. */
class LoadedSourcesArguments {}

/** Response to 'loadedSources' request. */
class LoadedSourcesResponse extends Response {
    LoadedSourcesResponseBody body;
}

class LoadedSourcesResponseBody {
    /** Set of loaded sources. */
    Source[] sources;
}

/**
 * Evaluate request; value of command field is 'evaluate'. Evaluates the given expression in the context of the top most
 * stack frame. The expression has access to any variables and arguments that are in scope.
 */
class EvaluateRequest extends Request {
    // command: 'evaluate';
    EvaluateArguments arguments;
}

/** Arguments for 'evaluate' request. */
class EvaluateArguments {
    /** The expression to evaluate. */
    String expression;
    /**
     * Evaluate the expression in the scope of this stack frame. If not specified, the expression is evaluated in the
     * global scope.
     */
    Integer frameId;
    /**
     * The context in which the evaluate request is run. Values: 'watch': evaluate is run in a watch. 'repl': evaluate
     * is run from REPL console. 'hover': evaluate is run from a data hover. etc.
     */
    String context;
    /** Specifies details on how to format the Evaluate result. */
    ValueFormat format;
}

/** Response to 'evaluate' request. */
class EvaluateResponse extends Response {
    EvaluateResponseBody body;
}

class EvaluateResponseBody {
    /** The result of the evaluate request. */
    String result;
    /** The optional type of the evaluate result. */
    String type;
    /** Properties of a evaluate result that can be used to determine how to render the result in the UI. */
    VariablePresentationHint presentationHint;
    /**
     * If variablesReference is > 0, the evaluate result is structured and its children can be retrieved by passing
     * variablesReference to the VariablesRequest.
     */
    int variablesReference;
    /**
     * The number of named child variables. The client can use this optional information to present the variables in a
     * paged UI and fetch them in chunks.
     */
    Integer namedVariables;
    /**
     * The number of indexed child variables. The client can use this optional information to present the variables in a
     * paged UI and fetch them in chunks.
     */
    Integer indexedVariables;
    /**
     * Memory reference to a location appropriate for this result. For pointer type eval results, this is generally a
     * reference to the memory address contained in the pointer.
     */
    String memoryReference;
}

/**
 * SetExpression request; value of command field is 'setExpression'. Evaluates the given 'value' expression and assigns
 * it to the 'expression' which must be a modifiable l-value. The expressions have access to any variables and arguments
 * that are in scope of the specified frame.
 */
class SetExpressionRequest extends Request {
    // command: 'setExpression';
    SetExpressionArguments arguments;
}

/** Arguments for 'setExpression' request. */
class SetExpressionArguments {
    /** The l-value expression to assign to. */
    String expression;
    /** The value expression to assign to the l-value expression. */
    String value;
    /**
     * Evaluate the expressions in the scope of this stack frame. If not specified, the expressions are evaluated in the
     * global scope.
     */
    Integer frameId;
    /** Specifies how the resulting value should be formatted. */
    ValueFormat format;
}

/** Response to 'setExpression' request. */
class SetExpressionResponse extends Response {
    SetExpressionResponseBody body;
}

class SetExpressionResponseBody {
    /** The new value of the expression. */
    String value;
    /** The optional type of the value. */
    String type;
    /** Properties of a value that can be used to determine how to render the result in the UI. */
    VariablePresentationHint presentationHint;
    /**
     * If variablesReference is > 0, the value is structured and its children can be retrieved by passing
     * variablesReference to the VariablesRequest.
     */
    Integer variablesReference;
    /**
     * The number of named child variables. The client can use this optional information to present the variables in a
     * paged UI and fetch them in chunks.
     */
    Integer namedVariables;
    /**
     * The number of indexed child variables. The client can use this optional information to present the variables in a
     * paged UI and fetch them in chunks.
     */
    Integer indexedVariables;
}

/**
 * StepInTargets request; value of command field is 'stepInTargets'. This request retrieves the possible stepIn targets
 * for the specified stack frame. These targets can be used in the 'stepIn' request. The StepInTargets may only be
 * called if the 'supportsStepInTargetsRequest' capability exists and is true.
 */
class StepInTargetsRequest extends Request {
    // command: 'stepInTargets';
    StepInTargetsArguments arguments;
}

/** Arguments for 'stepInTargets' request. */
class StepInTargetsArguments {
    /** The stack frame for which to retrieve the possible stepIn targets. */
    int frameId;
}

/** Response to 'stepInTargets' request. */
class StepInTargetsResponse extends Response {
    StepInTargetsResponseBody body;
}

class StepInTargetsResponseBody {
    /** The possible stepIn targets of the specified source location. */
    StepInTarget[] targets;
}

/**
 * GotoTargets request; value of command field is 'gotoTargets'. This request retrieves the possible goto targets for
 * the specified source location. These targets can be used in the 'goto' request. The GotoTargets request may only be
 * called if the 'supportsGotoTargetsRequest' capability exists and is true.
 */
class GotoTargetsRequest extends Request {
    // command: 'gotoTargets';
    GotoTargetsArguments arguments;
}

/** Arguments for 'gotoTargets' request. */
class GotoTargetsArguments {
    /** The source location for which the goto targets are determined. */
    Source source;
    /** The line location for which the goto targets are determined. */
    int line;
    /** An optional column location for which the goto targets are determined. */
    Integer column;
}

/** Response to 'gotoTargets' request. */
class GotoTargetsResponse extends Response {
    GotoTargetsResponseBody body;
}

class GotoTargetsResponseBody {
    /** The possible goto targets of the specified location. */
    GotoTarget[] targets;
}

/**
 * Completions request; value of command field is 'completions'. Returns a list of possible completions for a given
 * caret position and text. The CompletionsRequest may only be called if the 'supportsCompletionsRequest' capability
 * exists and is true.
 */
class CompletionsRequest extends Request {
    // command: 'completions';
    CompletionsArguments arguments;
}

/** Arguments for 'completions' request. */
class CompletionsArguments {
    /**
     * Returns completions in the scope of this stack frame. If not specified, the completions are returned for the
     * global scope.
     */
    Integer frameId;
    /**
     * One or more source lines. Typically this is the text a user has typed into the debug console before he asked for
     * completion.
     */
    String text;
    /** The character position for which to determine the completion proposals. */
    int column;
    /**
     * An optional line for which to determine the completion proposals. If missing the first line of the text is
     * assumed.
     */
    Integer line;
}

/** Response to 'completions' request. */
class CompletionsResponse extends Response {
    CompletionsResponseBody body;
}

class CompletionsResponseBody {
    /** The possible completions for . */
    CompletionItem[] targets;
}

/**
 * ExceptionInfo request; value of command field is 'exceptionInfo'. Retrieves the details of the exception that caused
 * this event to be raised.
 */
class ExceptionInfoRequest extends Request {
    // command: 'exceptionInfo';
    ExceptionInfoArguments arguments;
}

/** Arguments for 'exceptionInfo' request. */
class ExceptionInfoArguments {
    /** Thread for which exception information should be retrieved. */
    int threadId;
}

/** Response to 'exceptionInfo' request. */
class ExceptionInfoResponse extends Response {
    ExceptionInfoResponseBody body;
}

class ExceptionInfoResponseBody {
    /** ID of the exception that was thrown. */
    String exceptionId;
    /** Descriptive text for the exception provided by the debug adapter. */
    String description;
    /**
     * Mode that caused the exception notification to be raised. never: never breaks, always: always breaks, unhandled:
     * breaks when excpetion unhandled, userUnhandled: breaks if the exception is not handled by user code.
     */
    String breakMode;
    /** Detailed information about the exception. */
    ExceptionDetails details;
}

/** ReadMemory request; value of command field is 'readMemory'. Reads bytes from memory at the provided location. */
class ReadMemoryRequest extends Request {
    // command: 'readMemory';
    ReadMemoryArguments arguments;
}

/** Arguments for 'readMemory' request. */
class ReadMemoryArguments {
    /** Memory reference to the base location from which data should be read. */
    String memoryReference;
    /** Optional offset (in bytes) to be applied to the reference location before reading data. Can be negative. */
    Integer offset;
    /** Number of bytes to read at the specified location and offset. */
    int count;
}

/** Response to 'readMemory' request. */
class ReadMemoryResponse extends Response {
    ReadMemoryResponseBody body;
}

class ReadMemoryResponseBody {
    /**
     * The address of the first byte of data returned. Treated as a hex value if prefixed with '0x', or as a decimal
     * value otherwise.
     */
    String address;
    /**
     * The number of unreadable bytes encountered after the last successfully read byte. This can be used to determine
     * the number of bytes that must be skipped before a subsequent 'readMemory' request will succeed.
     */
    Integer unreadableBytes;
    /** The bytes read from memory, encoded using base64. */
    String data;
}

/** Disassemble request; value of command field is 'disassemble'. Disassembles code stored at the provided location. */
class DisassembleRequest extends Request {
    // command: 'disassemble';
    DisassembleArguments arguments;
}

/** Arguments for 'disassemble' request. */
class DisassembleArguments {
    /** Memory reference to the base location containing the instructions to disassemble. */
    String memoryReference;
    /** Optional offset (in bytes) to be applied to the reference location before disassembling. Can be negative. */
    Integer offset;
    /**
     * Optional offset (in instructions) to be applied after the byte offset (if any) before disassembling. Can be
     * negative.
     */
    Integer instructionOffset;
    /**
     * Number of instructions to disassemble starting at the specified location and offset. An adapter must return
     * exactly this number of instructions - any unavailable instructions should be replaced with an
     * implementation-defined 'invalid instruction' value.
     */
    int instructionCount;
    /** If true, the adapter should attempt to resolve memory addresses and other values to symbolic names. */
    Boolean resolveSymbols;
}

/** Response to 'disassemble' request. */
class DisassembleResponse extends Response {
    DisassembleResponseBody body;
}

class DisassembleResponseBody {
    /** The list of disassembled instructions. */
    DisassembledInstruction[] instructions;
}

/** Information about the capabilities of a debug adapter. */
class Capabilities {
    /** The debug adapter supports the 'configurationDone' request. */
    Boolean supportsConfigurationDoneRequest;
    /** The debug adapter supports function breakpoints. */
    Boolean supportsFunctionBreakpoints;
    /** The debug adapter supports conditional breakpoints. */
    Boolean supportsConditionalBreakpoints;
    /** The debug adapter supports breakpoints that break execution after a specified number of hits. */
    Boolean supportsHitConditionalBreakpoints;
    /** The debug adapter supports a (side effect free) evaluate request for data hovers. */
    Boolean supportsEvaluateForHovers;
    /** Available filters or options for the setExceptionBreakpoints request. */
    ExceptionBreakpointsFilter[] exceptionBreakpointFilters;
    /** The debug adapter supports stepping back via the 'stepBack' and 'reverseContinue' requests. */
    Boolean supportsStepBack;
    /** The debug adapter supports setting a variable to a value. */
    Boolean supportsSetVariable;
    /** The debug adapter supports restarting a frame. */
    Boolean supportsRestartFrame;
    /** The debug adapter supports the 'gotoTargets' request. */
    Boolean supportsGotoTargetsRequest;
    /** The debug adapter supports the 'stepInTargets' request. */
    Boolean supportsStepInTargetsRequest;
    /** The debug adapter supports the 'completions' request. */
    Boolean supportsCompletionsRequest;
    /** The debug adapter supports the 'modules' request. */
    Boolean supportsModulesRequest;
    /** The set of additional module information exposed by the debug adapter. */
    ColumnDescriptor[] additionalModuleColumns;
    /** Checksum algorithms supported by the debug adapter. 'MD5' | 'SHA1' | 'SHA256' | 'timestamp'. */
    String[] supportedChecksumAlgorithms;
    /**
     * The debug adapter supports the 'restart' request. In this case a client should not implement 'restart' by
     * terminating and relaunching the adapter but by calling the RestartRequest.
     */
    Boolean supportsRestartRequest;
    /** The debug adapter supports 'exceptionOptions' on the setExceptionBreakpoints request. */
    Boolean supportsExceptionOptions;
    /**
     * The debug adapter supports a 'format' attribute on the stackTraceRequest, variablesRequest, and evaluateRequest.
     */
    Boolean supportsValueFormattingOptions;
    /** The debug adapter supports the 'exceptionInfo' request. */
    Boolean supportsExceptionInfoRequest;
    /** The debug adapter supports the 'terminateDebuggee' attribute on the 'disconnect' request. */
    Boolean supportTerminateDebuggee;
    /**
     * The debug adapter supports the delayed loading of parts of the stack, which requires that both the 'startFrame'
     * and 'levels' arguments and the 'totalFrames' result of the 'StackTrace' request are supported.
     */
    Boolean supportsDelayedStackTraceLoading;
    /** The debug adapter supports the 'loadedSources' request. */
    Boolean supportsLoadedSourcesRequest;
    /** The debug adapter supports logpoints by interpreting the 'logMessage' attribute of the SourceBreakpoint. */
    Boolean supportsLogPoints;
    /** The debug adapter supports the 'terminateThreads' request. */
    Boolean supportsTerminateThreadsRequest;
    /** The debug adapter supports the 'setExpression' request. */
    Boolean supportsSetExpression;
    /** The debug adapter supports the 'terminate' request. */
    Boolean supportsTerminateRequest;
    /** The debug adapter supports data breakpoints. */
    Boolean supportsDataBreakpoints;
    /** The debug adapter supports the 'readMemory' request. */
    Boolean supportsReadMemoryRequest;
    /** The debug adapter supports the 'disassemble' request. */
    Boolean supportsDisassembleRequest;
}

/** An ExceptionBreakpointsFilter is shown in the UI as an option for configuring how exceptions are dealt with. */
class ExceptionBreakpointsFilter {
    /** The internal ID of the filter. This value is passed to the setExceptionBreakpoints request. */
    String filter;
    /** The name of the filter. This will be shown in the UI. */
    String label;
    /** Initial value of the filter. If not specified a value 'false' is assumed. */
    @SerializedName("default")
    Boolean _default;
}

/** A structured message object. Used to return errors from requests. */
class Message {
    /** Unique identifier for the message. */
    int id;
    /**
     * A format string for the message. Embedded variables have the form '{name}'. If variable name starts with an
     * underscore character, the variable does not contain user data (PII) and can be safely used for telemetry
     * purposes.
     */
    String format;
    /** An object used as a dictionary for looking up the variables in the format string. */
    Map<String, String> variables;
    /** If true send to telemetry. */
    Boolean sendTelemetry;
    /** If true show user. */
    Boolean showUser;
    /** An optional url where additional information about this message can be found. */
    String url;
    /** An optional label that is presented to the user as the UI for opening the url. */
    String urlLabel;
}

/**
 * A Module object represents a row in the modules view. Two attributes are mandatory: an id identifies a module in the
 * modules view and is used in a ModuleEvent for identifying a module for adding, updating or deleting. The name is used
 * to minimally render the module in the UI.
 *
 * <p>Additional attributes can be added to the module. They will show up in the module View if they have a
 * corresponding ColumnDescriptor.
 *
 * <p>To avoid an unnecessary proliferation of additional attributes with similar semantics but different names we
 * recommend to re-use attributes from the 'recommended' list below first, and only introduce new attributes if nothing
 * appropriate could be found.
 */
class Module {
    /** Unique identifier for the module. */
    String id;
    /** A name of the module. */
    String name;
    /**
     * optional but recommended attributes. always try to use these first before introducing additional attributes.
     *
     * <p>Logical full path to the module. The exact definition is implementation defined, but usually this would be a
     * full path to the on-disk file for the module.
     */
    String path;
    /** True if the module is optimized. */
    Boolean isOptimized;
    /** True if the module is considered 'user code' by a debugger that supports 'Just My Code'. */
    Boolean isUserCode;
    /** Version of Module. */
    String version;
    /**
     * User understandable description of if symbols were found for the module (ex: 'Symbols Loaded', 'Symbols not
     * found', etc.
     */
    String symbolStatus;
    /** Logical full path to the symbol file. The exact definition is implementation defined. */
    String symbolFilePath;
    /** Module created or modified. */
    String dateTimeStamp;
    /** Address range covered by this module. */
    String addressRange;
}

/**
 * A ColumnDescriptor specifies what module attribute to show in a column of the ModulesView, how to format it, and what
 * the column's label should be. It is only used if the underlying UI actually supports this level of customization.
 */
class ColumnDescriptor {
    /** Name of the attribute rendered in this column. */
    String attributeName;
    /** Header UI label of column. */
    String label;
    /** Format to use for the rendered values in this column. TBD how the format strings looks like. */
    String format;
    /**
     * Datatype of values in this column. Defaults to 'string' if not specified. 'string' | 'number' | 'boolean' |
     * 'unixTimestampUTC'.
     */
    String type;
    /** Width of this column in characters (hint only). */
    Integer width;
}

/**
 * The ModulesViewDescriptor is the container for all declarative configuration options of a ModuleView. For now it only
 * specifies the columns to be shown in the modules view.
 */
class ModulesViewDescriptor {
    ColumnDescriptor[] columns;
}

/** A Thread */
class Thread {
    /** Unique identifier for the thread. */
    int id;
    /** A name of the thread. */
    String name;
}

/**
 * A Source is a descriptor for source code. It is returned from the debug adapter as part of a StackFrame and it is
 * used by clients when specifying breakpoints.
 */
class Source {
    /**
     * The short name of the source. Every source returned from the debug adapter has a name. When sending a source to
     * the debug adapter this name is optional.
     */
    String name;
    /**
     * The path of the source to be shown in the UI. It is only used to locate and load the content of the source if no
     * sourceReference is specified (or its value is 0).
     */
    String path;
    /**
     * If sourceReference > 0 the contents of the source must be retrieved through the SourceRequest (even if a path is
     * specified). A sourceReference is only valid for a session, so it must not be used to persist a source.
     */
    Integer sourceReference;
    /**
     * An optional hint for how to present the source in the UI. A value of 'deemphasize' can be used to indicate that
     * the source is not available or that it is skipped on stepping. 'normal' | 'emphasize' | 'deemphasize'.
     */
    String presentationHint;
    /**
     * The (optional) origin of this source: possible values 'internal module', 'inlined content from source map', etc.
     */
    String origin;
    /**
     * An optional list of sources that are related to this source. These may be the source that generated this source.
     */
    Source[] sources;
    /**
     * Optional data that a debug adapter might want to loop through the client. The client should leave the data intact
     * and persist it across sessions. The client should not interpret the data.
     */
    JsonObject adapterData;
    /** The checksums associated with this file. */
    Checksum[] checksums;
}

/** A Stackframe contains the source location. */
class StackFrame {
    /**
     * An identifier for the stack frame. It must be unique across all threads. This id can be used to retrieve the
     * scopes of the frame with the 'scopesRequest' or to restart the execution of a stackframe.
     */
    int id;
    /** The name of the stack frame, typically a method name. */
    String name;
    /** The optional source of the frame. */
    Source source;
    /** The line within the file of the frame. If source is null or doesn't exist, line is 0 and must be ignored. */
    int line;
    /** The column within the line. If source is null or doesn't exist, column is 0 and must be ignored. */
    int column;
    /** An optional end line of the range covered by the stack frame. */
    Integer endLine;
    /** An optional end column of the range covered by the stack frame. */
    Integer endColumn;
    /** Optional memory reference for the current instruction pointer in this frame. */
    String instructionPointerReference;
    /** The module associated with this frame, if any. */
    String moduleId;
    /**
     * An optional hint for how to present this frame in the UI. A value of 'label' can be used to indicate that the
     * frame is an artificial frame that is used as a visual label or separator. A value of 'subtle' can be used to
     * change the appearance of a frame in a 'subtle' way. 'normal' | 'label' | 'subtle'.
     */
    String presentationHint;
}

/** A Scope is a named container for variables. Optionally a scope can map to a source or a range within a source. */
class Scope {
    /**
     * Name of the scope such as 'Arguments', 'Locals', or 'Registers'. This string is shown in the UI as is and can be
     * translated.
     */
    String name;
    /**
     * An optional hint for how to present this scope in the UI. If this attribute is missing, the scope is shown with a
     * generic UI. Values: 'arguments': Scope contains method arguments. 'locals': Scope contains local variables.
     * 'registers': Scope contains registers. Only a single 'registers' scope should be returned from a 'scopes'
     * request. etc.
     */
    String presentationHint;
    /**
     * The variables of this scope can be retrieved by passing the value of variablesReference to the VariablesRequest.
     */
    int variablesReference;
    /**
     * The number of named variables in this scope. The client can use this optional information to present the
     * variables in a paged UI and fetch them in chunks.
     */
    Integer namedVariables;
    /**
     * The number of indexed variables in this scope. The client can use this optional information to present the
     * variables in a paged UI and fetch them in chunks.
     */
    Integer indexedVariables;
    /** If true, the number of variables in this scope is large or expensive to retrieve. */
    boolean expensive;
    /** Optional source for this scope. */
    Source source;
    /** Optional start line of the range covered by this scope. */
    Integer line;
    /** Optional start column of the range covered by this scope. */
    Integer column;
    /** Optional end line of the range covered by this scope. */
    Integer endLine;
    /** Optional end column of the range covered by this scope. */
    Integer endColumn;
}

/**
 * A Variable is a name/value pair. Optionally a variable can have a 'type' that is shown if space permits or when
 * hovering over the variable's name. An optional 'kind' is used to render additional properties of the variable, e.g.
 * different icons can be used to indicate that a variable is public or private. If the value is structured (has
 * children), a handle is provided to retrieve the children with the VariablesRequest. If the number of named or indexed
 * children is large, the numbers should be returned via the optional 'namedVariables' and 'indexedVariables'
 * attributes. The client can use this optional information to present the children in a paged UI and fetch them in
 * chunks.
 */
class Variable {
    /** The variable's name. */
    String name;
    /** The variable's value. This can be a multi-line text, e.g. for a function the body of a function. */
    String value;
    /** The type of the variable's value. Typically shown in the UI when hovering over the value. */
    String type;
    /** Properties of a variable that can be used to determine how to render the variable in the UI. */
    VariablePresentationHint presentationHint;
    /**
     * Optional evaluatable name of this variable which can be passed to the 'EvaluateRequest' to fetch the variable's
     * value.
     */
    String evaluateName;
    /**
     * If variablesReference is > 0, the variable is structured and its children can be retrieved by passing
     * variablesReference to the VariablesRequest.
     */
    int variablesReference;
    /**
     * The number of named child variables. The client can use this optional information to present the children in a
     * paged UI and fetch them in chunks.
     */
    Integer namedVariables;
    /**
     * The number of indexed child variables. The client can use this optional information to present the children in a
     * paged UI and fetch them in chunks.
     */
    Integer indexedVariables;
    /**
     * Optional memory reference for the variable if the variable represents executable code, such as a function
     * pointer.
     */
    String memoryReference;
}

/** Optional properties of a variable that can be used to determine how to render the variable in the UI. */
class VariablePresentationHint {
    /**
     * The kind of variable. Before introducing additional values, try to use the listed values. Values: 'property':
     * Indicates that the object is a property. 'method': Indicates that the object is a method. 'class': Indicates that
     * the object is a class. 'data': Indicates that the object is data. 'event': Indicates that the object is an event.
     * 'baseClass': Indicates that the object is a base class. 'innerClass': Indicates that the object is an inner
     * class. 'interface': Indicates that the object is an interface. 'mostDerivedClass': Indicates that the object is
     * the most derived class. 'virtual': Indicates that the object is virtual, that means it is a synthetic object
     * introduced by the adapter for rendering purposes, e.g. an index range for large arrays. 'dataBreakpoint':
     * Indicates that a data breakpoint is registered for the object. etc.
     */
    String kind;
    /**
     * Set of attributes represented as an array of strings. Before introducing additional values, try to use the listed
     * values. Values: 'static': Indicates that the object is static. 'constant': Indicates that the object is a
     * constant. 'readOnly': Indicates that the object is read only. 'rawString': Indicates that the object is a raw
     * string. 'hasObjectId': Indicates that the object can have an Object ID created for it. 'canHaveObjectId':
     * Indicates that the object has an Object ID associated with it. 'hasSideEffects': Indicates that the evaluation
     * had side effects. etc.
     */
    String attributes[];
    /**
     * Visibility of variable. Before introducing additional values, try to use the listed values. Values: 'public',
     * 'private', 'protected', 'internal', 'final', etc.
     */
    String visibility;
}

/** Properties of a breakpoint or logpoint passed to the setBreakpoints request. */
class SourceBreakpoint {
    /** The source line of the breakpoint or logpoint. */
    int line;
    /** An optional source column of the breakpoint. */
    Integer column;
    /** An optional expression for conditional breakpoints. */
    String condition;
    /**
     * An optional expression that controls how many hits of the breakpoint are ignored. The backend is expected to
     * interpret the expression as needed.
     */
    String hitCondition;
    /**
     * If this attribute exists and is non-empty, the backend must not 'break' (stop) but log the message instead.
     * Expressions within {} are interpolated.
     */
    String logMessage;
}

/** Properties of a breakpoint passed to the setFunctionBreakpoints request. */
class FunctionBreakpoint {
    /** The name of the function. */
    String name;
    /** An optional expression for conditional breakpoints. */
    String condition;
    /**
     * An optional expression that controls how many hits of the breakpoint are ignored. The backend is expected to
     * interpret the expression as needed.
     */
    String hitCondition;
}

/** Properties of a data breakpoint passed to the setDataBreakpoints request. */
class DataBreakpoint {
    /** An id representing the data. This id is returned from the dataBreakpointInfo request. */
    String dataId;
    /** The access type of the data. 'read' | 'write' | 'readWrite'. */
    String accessType;
    /** An optional expression for conditional breakpoints. */
    String condition;
    /**
     * An optional expression that controls how many hits of the breakpoint are ignored. The backend is expected to
     * interpret the expression as needed.
     */
    String hitCondition;
}

/** Information about a Breakpoint created in setBreakpoints or setFunctionBreakpoints. */
class Breakpoint {
    /**
     * An optional identifier for the breakpoint. It is needed if breakpoint events are used to update or remove
     * breakpoints.
     */
    Integer id;
    /** If true breakpoint could be set (but not necessarily at the desired location). */
    boolean verified;
    /**
     * An optional message about the state of the breakpoint. This is shown to the user and can be used to explain why a
     * breakpoint could not be verified.
     */
    String message;
    /** The source where the breakpoint is located. */
    Source source;
    /** The start line of the actual range covered by the breakpoint. */
    Integer line;
    /** An optional start column of the actual range covered by the breakpoint. */
    Integer column;
    /** An optional end line of the actual range covered by the breakpoint. */
    Integer endLine;
    /**
     * An optional end column of the actual range covered by the breakpoint. If no end line is given, then the end
     * column is assumed to be in the start line.
     */
    Integer endColumn;
}

/**
 * A StepInTarget can be used in the 'stepIn' request and determines into which single target the stepIn request should
 * step.
 */
class StepInTarget {
    /** Unique identifier for a stepIn target. */
    int id;
    /** The name of the stepIn target (shown in the UI). */
    String label;
}

/**
 * A GotoTarget describes a code location that can be used as a target in the 'goto' request. The possible goto targets
 * can be determined via the 'gotoTargets' request.
 */
class GotoTarget {
    /** Unique identifier for a goto target. This is used in the goto request. */
    int id;
    /** The name of the goto target (shown in the UI). */
    String label;
    /** The line of the goto target. */
    int line;
    /** An optional column of the goto target. */
    Integer column;
    /** An optional end line of the range covered by the goto target. */
    Integer endLine;
    /** An optional end column of the range covered by the goto target. */
    Integer endColumn;
    /** Optional memory reference for the instruction pointer value represented by this target. */
    String instructionPointerReference;
}

/** CompletionItems are the suggestions returned from the CompletionsRequest. */
class CompletionItem {
    /**
     * The label of this completion item. By default this is also the text that is inserted when selecting this
     * completion.
     */
    String label;
    /** If text is not falsy then it is inserted instead of the label. */
    String text;
    /**
     * The item's type. Typically the client uses this information to render the item in the UI with an icon. 'method' |
     * 'function' | 'constructor' | 'field' | 'variable' | 'class' | 'interface' | 'module' | 'property' | 'unit' |
     * 'value' | 'enum' | 'keyword' | 'snippet' | 'text' | 'color' | 'file' | 'reference' | 'customcolor'.
     */
    String type;
    /**
     * This value determines the location (in the CompletionsRequest's 'text' attribute) where the completion text is
     * added. If missing the text is added at the location specified by the CompletionsRequest's 'column' attribute.
     */
    Integer start;
    /**
     * This value determines how many characters are overwritten by the completion text. If missing the value 0 is
     * assumed which results in the completion text being inserted.
     */
    Integer length;
}

/** The checksum of an item calculated by the specified algorithm. */
class Checksum {
    /** The algorithm used to calculate this checksum. 'MD5' | 'SHA1' | 'SHA256' | 'timestamp'. */
    String algorithm;
    /** Value of the checksum. */
    String checksum;
}

/** Provides formatting information for a value. */
class ValueFormat {
    /** Display the value in hex. */
    Boolean hex;
}

/** Provides formatting information for a stack frame. */
class StackFrameFormat extends ValueFormat {
    /** Displays parameters for the stack frame. */
    Boolean parameters;
    /** Displays the types of parameters for the stack frame. */
    Boolean parameterTypes;
    /** Displays the names of parameters for the stack frame. */
    Boolean parameterNames;
    /** Displays the values of parameters for the stack frame. */
    Boolean parameterValues;
    /** Displays the line number of the stack frame. */
    Boolean line;
    /** Displays the module of the stack frame. */
    Boolean module;
    /** Includes all stack frames, including those the debug adapter might otherwise hide. */
    Boolean includeAll;
}

/** An ExceptionOptions assigns configuration options to a set of exceptions. */
class ExceptionOptions {
    /**
     * A path that selects a single or multiple exceptions in a tree. If 'path' is missing, the whole tree is selected.
     * By convention the first segment of the path is a category that is used to group exceptions in the UI.
     */
    ExceptionPathSegment[] path;
    /**
     * Condition when a thrown exception should result in a break. never: never breaks, always: always breaks,
     * unhandled: breaks when excpetion unhandled, userUnhandled: breaks if the exception is not handled by user code.
     */
    String breakMode;
}

/**
 * An ExceptionPathSegment represents a segment in a path that is used to match leafs or nodes in a tree of exceptions.
 * If a segment consists of more than one name, it matches the names provided if 'negate' is false or missing or it
 * matches anything except the names provided if 'negate' is true.
 */
class ExceptionPathSegment {
    /**
     * If false or missing this segment matches the names provided, otherwise it matches anything except the names
     * provided.
     */
    Boolean negate;
    /** Depending on the value of 'negate' the names that should match or not match. */
    String[] names;
}

/** Detailed information about an exception that has occurred. */
class ExceptionDetails {
    /** Message contained in the exception. */
    String message;
    /** Short type name of the exception object. */
    String typeName;
    /** Fully-qualified type name of the exception object. */
    String fullTypeName;
    /** Optional expression that can be evaluated in the current scope to obtain the exception object. */
    String evaluateName;
    /** Stack trace at the time the exception was thrown. */
    String stackTrace;
    /** Details of the exception contained by this exception, if any. */
    ExceptionDetails[] innerException;
}

/** Represents a single disassembled instruction. */
class DisassembledInstruction {
    /**
     * The address of the instruction. Treated as a hex value if prefixed with '0x', or as a decimal value otherwise.
     */
    String address;
    /** Optional raw bytes representing the instruction and its operands, in an implementation-defined format. */
    String instructionBytes;
    /** Text representing the instruction and its operands, in an implementation-defined format. */
    String instruction;
    /** Name of the symbol that correponds with the location of this instruction, if any. */
    String symbol;
    /**
     * Source location that corresponds to this instruction, if any. Should always be set (if available) on the first
     * instruction returned, but can be omitted afterwards if this instruction maps to the same source file as the
     * previous instruction.
     */
    Source location;
    /** The line within the source location that corresponds to this instruction, if any. */
    Integer line;
    /** The column within the line that corresponds to this instruction, if any. */
    Integer column;
    /** The end line of the range that corresponds to this instruction, if any. */
    Integer endLine;
    /** The end column of the range that corresponds to this instruction, if any. */
    Integer endColumn;
}
