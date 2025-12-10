# Design Discussions

This file captures the design discussions and decisions made for the MCP server implementation.

---

## Initial Requirements (from user)

The IntelliJ plugin must start an MCP server with the following goals:

1. **Kotlin/Groovy Console Interface**: Offer a console interface to the requestor where user writes code executed in IntelliJ's own classpath.

2. **McpScriptContext Class**: A static class sent to execution context with functions to simplify execution.

3. **Code Execution Model**:
   - Code submitted to MCP server creates a dedicated classloader
   - Classloader has necessary plugins as parents
   - List of plugins is an API parameter
   - Classes are loaded and executed
   - Requestor sees output or compilation errors

4. **Entry Point**: Clear semantics for the entry point, promoted to MCP server specification.

5. **Code Storage**: All submitted code stored under `.idea/mcp-run/` folder.

6. **Single Code Block**: MCP server receives only one code block at a time.

7. **Dynamic Commands**: McpScriptContext allows code to register new MCP commands that run in background.

8. **Blocking Execution**: Code executes in IDE context as blocking function, call completes when function exits.

9. **Slots API**: Well-documented slots API for read/write to named slots.

10. **Project-Scoped**: List open projects with directories, all requests executed per-project (required parameter). McpScriptContext has getter for current project.

11. **Java Services for Compilation**: Use Java services to compile code, run in the classpath of code execution.

12. **IntelliJ Project Detection**: Special case where MCP server detects it's working on IntelliJ project and provides enhanced context.

13. **LLM Documentation**: Documentation must refer to https://github.com/intellij-community for API reference.

14. **Code Review Mode**: Mode (activated by default) where agent code is opened in editor for human review and manual approval before execution. Later: 3rd party tool integration for automatic checking.

15. **Reflection Guidance**: Recommend LLM to use reflection to understand runtime capabilities.

---

## Q&A Session

### MCP Protocol & Transport

**Q1: MCP Protocol Compliance** - Standard MCP uses stdio transport. Are you intentionally using HTTP instead?

**A1**: Yes, we use HTTP/TCP as the transport. Add standard documentation on how to connect it via stdio (proxy/adapter).

**Q2: Why port 11993?** Is this arbitrary or configurable?

**A2**: Port is configurable via IntelliJ Registry, 11993 is default. If port is busy, allocate another port automatically.

---

### Entry Point & Code Structure

**Q3: Return value handling** - How should scripts return data to the LLM?

**A3**: We cannot listen to stdout, so provide methods in context:
- `println` family of functions in context for output
- Function to send JSON back (use Gson or Jackson to serialize objects)
- Function to log info/warn/errors

**Q4: Imports in submitted code** - Should code include imports or auto-import?

**A4**: Add predefined set of `*` imports, document them, let agent know. It's assumed imports are included in code.

**Q5: Package declaration** - Required?

**A5**: Package declaration is optional and should not change the logic.

---

### Code Review Mode

**Q6: Blocking behavior during review** - How should LLM handle pending review?

**A6**:
- Make the call waiting (blocking)
- If protocol allows, let LLM know code waits approval
- Editing will not change the code
- Rejection message should include all changes and comments user made in file
- **Suggestion**: Make IntelliJ resolve the code in the correct (same) classpath

**Q7: What happens if user edits code?**

**A7**: User can only send the edited code back together with the rejection message.

---

### Classloader & Compilation

**Q8: Kotlin compiler availability** - How to handle Kotlin stdlib?

**A8**: Put the library onto our plugin classpath and use that to compile the code.

**Q9: Script dependencies on other scripts** - Can scripts import from previous executions?

**A9**: Script always executed in fresh classloader. No dependencies to previous code allowed. But it's the same JVM, so many ways to interact indirectly exist.

---

### Slots API

**Q10: Slot data types** - String only or structured?

**A10**: Support both JSON and String. But be careful - can only keep Strings inside, not objects (classloader gets GC-ed).

**Q11: Slot scope** - Project-scoped or global?

**A11**: Everything is project-scoped. Can be a project service in IntelliJ plugin, same as McpContext object.

---

### Dynamic Commands

**Q12: Handler execution context** - What classloader runs the handler?

**A12**: For every script execution:
1. Create a new platform classloader
2. Set all necessary plugins as parent classloaders (parameter of call)
3. Use classpath to compile the code
4. Use same classloader to execute the code
5. Once done, classloader will be GC-ed
6. Must not keep classloader in memory

**Q13: Command parameters** - What does ParameterSpec look like?

**A13**: Allow passing a string as command parameters, code can access it. (Related to Q12 - keep it simple due to classloader lifecycle)

---

### Plugin Dependencies

**Q14: When to add plugin dependencies** - Explicit call or in-code declaration?

**A14**: `add_dependency` is just a parameter to the code execution call, no additional action needed.

**Q15: Default plugins** - Which plugins included by default?

**A15**: Use all plugins as default. **TODO**: Ask for details later, mark this for follow-up.

---

### Error Handling

**Q16: Compilation vs Runtime errors** - Different response structures?

**A16**: Yes, must be as specific as possible to help LLM solve its own problem on first attempt.

**Q17: Partial output on failure** - Include output before exception?

**A17**:
- Stream output as it appears
- Clearly deliver execution status at the end
- Make sure messages are not intermixed in output
- If possible, stream to LLM ASAP

---

### Practical Usage

**Q18: Concurrency** - What if multiple requests arrive?

**A18**: Run requests one-by-one. There must be an option for that (queue).

**Q19: IDE state during execution** - Can script access open editor, selection, etc.?

**A19**: Yes, script can access everything it wants to.

**Q20: Write actions** - Should scripts wrap in WriteAction or use helpers?

**A20**:
- Add information about read and write locks
- Import necessary primitives
- It's up to MCP server to generate correct code
- **Change**: Make entry point function `suspend` to simplify code where suspend is needed
- Still need to create coroutineScope and runBlocking to execute it

---

### Additional Requirements from Q&A

**Timeout and Cancellation**:
- Introduce timeout for function call
- API for MCP server to stop current command run
- Need recommendation on polling vs streaming

**Entry Point Change**:
```kotlin
suspend fun main(ctx: McpScriptContext) { ... }
```

---

## Open Questions Requiring Recommendation

### Streaming vs Polling for Long-Running Operations

**Context**: Need to handle:
1. Code review waiting for human approval
2. Long-running script execution
3. Streaming output to LLM
4. Timeout and cancellation

**Options**:

**Option A: Polling**
- `execute_code` returns immediately with `execution_id`
- LLM calls `get_execution_status(execution_id)` periodically
- Returns: pending/running/completed/failed/cancelled
- Pros: Simple, stateless, works with any MCP client
- Cons: Latency, extra round trips, LLM must implement polling logic

**Option B: Streaming (SSE/WebSocket)**
- `execute_code` opens stream
- Server pushes: status updates, stdout lines, completion
- Pros: Real-time, efficient
- Cons: More complex, may not be supported by all MCP clients

**Option C: Long-polling**
- `execute_code` blocks until completion or timeout
- If timeout, returns partial result + continuation token
- LLM calls again with token to continue
- Pros: Works with HTTP, feels synchronous
- Cons: Connection management, timeout handling

**Option D: Hybrid**
- Default: blocking call with configurable timeout
- Optional: streaming endpoint for real-time output
- `cancel_execution(execution_id)` available regardless
- Separate `get_output(execution_id)` to retrieve buffered output

**Recommendation**:

For MCP protocol compatibility and simplicity, I recommend **Option D (Hybrid)** with emphasis on:

1. **Primary mode**: Blocking call with timeout (e.g., 60 seconds default)
   - Simple for LLM to use
   - Returns complete result when done
   - If times out, returns what's available + `execution_id`

2. **Cancellation**: Always available via `cancel_execution(execution_id)`
   - Works even during code review wait

3. **Output buffering**: All output buffered and returned in response
   - For long operations, LLM can retrieve via `get_output(execution_id)` if needed

4. **Future streaming**: Add optional streaming endpoint later for real-time UX

This keeps the basic API simple (one blocking call) while providing escape hatches for complex scenarios.

---

## Decisions Summary

| Topic | Decision |
|-------|----------|
| Transport | HTTP/TCP, document stdio proxy |
| Port | 11993 default, configurable via Registry, auto-allocate if busy |
| Output | Context methods: println, JSON serialization, logging |
| Imports | Predefined * imports, documented |
| Package | Optional |
| Review blocking | Call waits, rejection includes user edits/comments |
| Classloader | Fresh per execution, GC-ed after |
| Slots | String and JSON, project-scoped |
| Commands | String parameters, fresh classloader per invocation |
| Dependencies | Parameter to execute_code |
| Default plugins | All plugins (details TBD) |
| Errors | Distinct compilation vs runtime errors |
| Output streaming | Stream as available, status at end |
| Concurrency | Sequential execution (queue) |
| Entry point | `suspend fun main(ctx: McpScriptContext)` |
| Timeout | Yes, with cancellation API |

---

## Items Marked for Follow-up

1. **Default plugins**: Need details on which plugins to include by default
2. **Stdio proxy documentation**: How to connect via stdio for standard MCP clients
3. **IntelliJ code resolution**: Make IntelliJ resolve code in same classpath (suggestion)
4. **Polling vs Streaming**: Recommendation provided above, awaiting confirmation
