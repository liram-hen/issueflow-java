# AI Usage — IssueFlow Assignment

**Model:** `claude-sonnet-4-6` (Claude Code, Anthropic)

---

## Session Overview

Claude Code was used interactively via the CLI throughout the implementation. The workflow was conversational — requirements were provided, the model audited the existing skeleton, identified gaps, proposed fixes with explanations, and applied them after explicit approval.

---

## Key Prompts

### 1. Requirements Analysis

**Prompt:**
> I'll give you the project requirements. I want you to make sure all of them are met. If you want to edit something in the project, explain why you want to make the change.
> *(followed by the full requirements document)*

**Purpose:** Gave the model the full spec so it could compare it against the existing skeleton and produce a structured gap analysis. The model read all relevant source files and returned an 11-point list of deviations, each with the relevant file, the requirement violated, and the proposed fix.

---

### 2. Applying Fixes

**Prompt:**
> Fix all of them, explain changes before making them.

**Purpose:** Triggered the implementation phase. The model explained each change and its rationale before writing any code. Changes covered: wrong enum values (`TASK` → `TECHNICAL`, missing `IN_REVIEW`), optimistic locking (`@Version`) on `Ticket` and `Comment`, persistent `isOverdue` flag, project-scoped auto-assignment workload, blocker check before DONE transition, same-project constraint on dependencies, missing soft-delete/restore endpoints for tickets, `AUTO_ASSIGN` audit action, and ADMIN-only security rules.

---

### 3. Clarification on `isOverdue` Logic

**Prompt:**
> Tell me where you check if it's overdue.

**Purpose:** The model paused and explained that the "overdue" determination is implicit in the repository query (`findAllByDeletedFalseAndStatusNotAndDueDateBefore`), and that `isOverdue` is set only when a ticket reaches CRITICAL priority — distinguishing it from a general past-due check.

---

### 4. Running Tests

**Prompt:**
> Run the tests.

**Purpose:** Verified correctness end-to-end. Three test failures were found — stale enum references (`TASK`, missing `IN_REVIEW`, missing `AUTO_ASSIGN`) and a test asserting the old dynamic `isOverdue` behavior. The model fixed each failure with an explanation, and all 56 tests passed.

---

### 5. Documentation

**Prompt:**
> Is there a `prompts.md` documenting how AI was used? Also is there a `run.md` with exact steps? If not, create them — keep it concise and professional.

**Purpose:** Generated this file and `run.md`.

---

### 6. Concurrent Update Error Handling

**Prompt:**
> The spec says tickets and comments can't be updated simultaneously by two users. The `@Version` field on `Ticket` and `Comment` enforces this via optimistic locking, but `GlobalExceptionHandler` doesn't catch `ObjectOptimisticLockingFailureException` — concurrent collisions return 500. Add a handler that returns 409 Conflict with a retry message.

**Purpose:** Patched the missing exception handler. Without it, two simultaneous `PATCH /tickets/{id}` requests would result in a 500 Internal Server Error for the losing request instead of a clear 409 Conflict. The fix adds a single `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)` method to `GlobalExceptionHandler` returning 409 with `"Resource was modified by another request, please retry"`.

---

## Notes

- Every code change was explained before being applied.
- Test files were updated only where the tests reflected the old (incorrect) behavior, not to mask real failures.
- The model had full read access to the source tree throughout the session.
