# AI Usage — IssueFlow Assignment

**Model:** `claude-sonnet-4-6` (Claude Code, Anthropic)

---

## Session Overview

Claude Code was used interactively via the CLI throughout the implementation. The workflow was conversational — requirements were provided, the model audited the existing skeleton, identified gaps, proposed fixes with explanations, and applied them after explicit approval.

A second, deeper session walked through every requirements section (§2.1–§3.8) end-to-end: verifying implementation correctness, finding untested edge cases, catching behavioral bugs, and expanding test coverage from 56 to 97 passing tests.

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

### 7. Full Requirements Walkthrough (§2.1–§3.8)

**Prompt:**
> are there tests for every segment in requirements? e.g. test for 2.1 user management...

**Purpose:** Triggered a section-by-section review of every requirement against both the implementation and the test suite. For each section the model identified: which behaviors were implemented correctly, which edge cases were untested, and which had behavioral bugs. Each gap was explained before being addressed.

**Outcome:** 41 new tests added across 6 test files, covering previously untested paths in every section.

---

### 8. Catching Behavioral Bugs During Review

During the walkthrough, several non-obvious bugs were found and fixed:

**Status transition skipping:**
> what happens if someone sends TODO → DONE directly?

The `validateForwardStatusMove` check used `!=` instead of `ordinal() != ordinal() + 1`, allowing any forward jump. Fixed to enforce strict one-step transitions.

**Comment ownership:**
> can an admin edit a comment of a dev? is it tested that only the author of the comment can edit it?

No ownership check existed — any authenticated user could edit any comment. Added a check throwing `403 Forbidden` unless the requester is the comment's author or an ADMIN.

**Circular dependency detection:**
> what happens if A blocks B and B blocks A?

No cycle detection existed — the system would accept both edges, creating a permanent deadlock. Added BFS cycle detection that rejects any dependency that would form a cycle (direct or indirect) with a descriptive 400 error.

**MIME type rejection for `text/plain; charset=utf-8`:**
> is stripping the best way to fix this?

`AttachmentService` compared the raw `Content-Type` header against the allowed list. A header like `text/plain; charset=utf-8` failed the check despite being valid. Fixed using Spring's `MediaType.parseMediaType()` to strip parameters before comparing.

**Escalation running every 60 seconds:**
> if a ticket is low priority and dueDate happens, should it stay MEDIUM until a new dueDate is set?

The escalation scheduler ran every tick on all overdue tickets — a LOW ticket would reach CRITICAL in 3 minutes. Added an `escalatedAt` timestamp to `Ticket`: set when escalated, cleared only when `dueDate` is updated. The scheduler skips any ticket where `escalatedAt IS NOT NULL`, giving one escalation step per dueDate event. A follow-up clarification also confirmed that manual priority changes should NOT re-enable escalation:

> if priority is manually changed and no new dueDate is set, the priority shouldn't escalate again

Removed the `escalatedAt = null` reset from the priority-change path, keeping it only on dueDate updates.

---

### 9. Auto-Assignment Edge Case Verification (§3.8)

**Prompt:**
> we need to make sure these work: [full §3.8 requirements list including least-loaded selection, tie-breaking, ADMIN exclusion, null assignee when no devs, project-scoped workload, no auto-assign on update]

**Purpose:** Despite the feature being implemented, none of the core auto-assignment behaviors had direct test coverage. Six targeted tests were added verifying: the least-loaded developer is selected, ties resolve to the oldest registrant, ADMIN users are excluded as candidates, a ticket is created with null assignee (no error, no audit log) when no DEVELOPERs exist, workload counts are scoped to the target project only, and PATCH without `assigneeId` never triggers re-assignment.

---

### 10. README Cross-Check and run.md Improvements

**Prompt:**
> check the readme to see if it matches the project, also make sure the run.md is readable and has everything it needs

**Purpose:** Verified all 36 API endpoints in `README.md` against the controllers — paths, HTTP methods, request bodies, and response shapes all matched. Identified one intentional divergence: `POST /users` requires a `password` field not shown in the README example (without it, accounts are permanently locked out since `POST /auth/login` would always fail).

`run.md` was expanded to cover: a bootstrap admin user seeded on first startup (solving the chicken-and-egg problem where `POST /users` requires auth but no users exist yet), Windows PowerShell command variants alongside Bash, a smoke test showing the full login → authenticated request flow, and a note explaining the `password` divergence from the README.

---

## Notes

- Every code change was explained before being applied.
- Test files were updated only where the tests reflected the old (incorrect) behavior, not to mask real failures.
- The model had full read access to the source tree throughout the session.
- Final test count: 97 passing, 0 failures.
