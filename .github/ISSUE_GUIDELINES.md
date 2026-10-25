# IPA Board — Issue Authoring Guidelines

- **Document Type:** Development Guidelines
- **Scope:** GitHub Issue Creation and Maintenance
- **Applies To:** Human Contributors and AI Automation Agents

## 1. Purpose

This document establishes consistent standards for creating and maintaining GitHub Issues in IPA Board.

The primary objectives are to:

- Clearly communicate bugs, feature requests, and other development tasks.
- Preserve the original intent of each request.
- Produce actionable and verifiable requirements.
- Maintain consistent terminology and formatting.
- Separate observed facts from assumptions and proposed implementations.
- Support future development, testing, Pull Requests, and release documentation.
- Enable AI agents to generate high-quality Issues without introducing unauthorized requirements.

An Issue should describe what needs to be accomplished, why it matters, and how completion can be verified.

Implementation details should only be prescribed when they are essential to the requested behavior or explicitly required by the requester.

## 2. General Principles

### 2.1 Accuracy Over Completeness

Never invent information to make an Issue appear more complete.

Do not fabricate:

- Reproduction steps.
- Testing results.
- Affected applications or devices.
- Confirmed root causes.
- Existing implementation details.
- Commit hashes or version numbers.
- Performance characteristics.
- Compatibility guarantees.

When necessary information is unavailable, omit the relevant section or mark it as "TBD".

### 2.2 Preserve User Intent

The Issue must accurately reflect the requested behavior.

Do not:

- Replace the requested feature with a different design.
- Introduce unrelated functionality.
- Change the intended interaction model.
- Assume that an existing feature needs to be reimplemented.
- Turn an optional capability into a mandatory one.
- Expand the initial implementation scope without authorization.

If the request explicitly limits the initial implementation, preserve that limitation.

### 2.3 Distinguish Requirements from Suggestions

Use clear distinctions between:

- **Requirements:** Behavior explicitly requested or necessary to fulfill the request.
- **Expected Behavior:** Observable results that the implementation must produce.
- **Acceptance Criteria:** Conditions that must be satisfied before the Issue can be considered complete.
- **Implementation Notes:** Optional technical recommendations.
- **Future Extensions:** Potential capabilities outside the current scope.

Do not silently convert implementation suggestions into mandatory requirements.

### 2.4 Avoid Overengineering

Keep each Issue focused on a coherent development objective.

Avoid introducing unnecessary:

- Configuration options.
- User interface controls.
- Architectural changes.
- Dependencies.
- Compatibility requirements.
- Error handling features unrelated to the request.

Technical recommendations should be proportional to the complexity of the requested change.

### 2.5 Use English

Write Issue titles and descriptions in clear, professional English.

Prefer concise, precise technical language over marketing language.

Use established Android, GitHub, and software engineering terminology whenever appropriate.

## 3. Issue Classification

Before generating an Issue, determine its type.

### 3.1 Bug Report

Use when existing functionality behaves incorrectly, fails, or provides inadequate feedback for an existing operation.

Examples:

- Candidate suggestions disappear after reopening the keyboard.
- The emoji scrollbar prevents selecting emoji.
- Caps Lock activates without a clear visual indication.

Recommended label: `bug`

### 3.2 Feature Request

Use when introducing a new capability or extending existing functionality.

Examples:

- Engine Dashboard.
- Continuous Cursor Movement functions.
- Per-keyboard-page engine configuration.
- Customizable key preview popup colors.

Recommended label: `enhancement`

### 3.3 Other Categories

Additional categories may be introduced as needed:

| Category | Suggested Label |
| --- | --- |
| Refactoring | `refactor` |
| Documentation | `documentation` |
| Performance | `performance` |
| Testing | `testing` |
| Maintenance | `maintenance` |

Do not create additional labels merely because a request mentions a particular subsystem.

### 3.4 Classification Rules

Classify Issues according to the nature of the requested change, not merely the wording of the request.

A request to improve the visibility of an existing functional state may be classified as a Bug.

A request to introduce new behavior should generally be classified as a Feature.

If classification is genuinely ambiguous, request clarification rather than making an unsupported assumption.

## 4. Issue Titles

### 4.1 General Format

Use a concise title describing the primary change.

Recommended format:

```text
<type>(<scope>): <description>
```

Examples:

- `fix(ime): Restore candidate suggestions after keyboard reopening`
- `fix(ui): Add visual indication for Caps Lock state`
- `feat: Add per-keyboard-page input engine configuration`
- `feat: Add four continuous cursor movement functions`

Alternatively, use:

- `[Bug] <description>`
- `[Feature] <description>`

Use one consistent convention within the repository.

### 4.2 Title Requirements

A title should:

- Identify the affected component or functionality.
- Describe the central problem or requested capability.
- Be understandable without reading the entire Issue.
- Avoid unnecessary implementation details.
- Avoid unverified root-cause claims.
- Remain concise.

### 4.3 Prohibited Title Practices

Do not manually prepend GitHub Issue numbers.

Incorrect:

```text
12-feature-add-engine-dashboard
```

Correct:

```text
feat: Add an engine dashboard accessible via a customizable key
```

GitHub assigns Issue numbers automatically.

Branch names, Issue titles, and Pull Request titles serve different purposes and should not be confused.

## 5. Bug Report Structure

Use the following structure when applicable.

### 5.1 Description

Explain the malfunction in a short paragraph.

Include:

- The affected functionality.
- The circumstances under which the problem occurs.
- The observable failure.
- The practical impact on the user.

Do not introduce unsupported explanations.

### 5.2 Steps to Reproduce

Provide numbered, reproducible steps.

Each step should describe an observable action.

Example:

1. Open an affected application.
2. Focus a text input field.
3. Enter text and confirm that candidate suggestions appear.
4. Dismiss the keyboard.
5. Reopen the same input field.
6. Enter text again.

Avoid vague instructions such as "use the keyboard normally."

Only include steps supported by the provided information.

### 5.3 Expected Behavior

Describe what should happen when the same actions are performed.

Expected behavior should be observable and testable.

### 5.4 Actual Behavior

Describe what actually happens.

Separate independent symptoms when useful.

Do not confuse symptoms with root causes.

### 5.5 Affected Components or Environments

When relevant, document:

- Input engines.
- Keyboard pages.
- Applications.
- Android versions.
- Device models.
- Project versions.
- Commit identifiers.

Use tables when comparing multiple environments or components.

Clearly distinguish successful tests, failed tests, and untested configurations.

### 5.6 Investigation and Root Cause

Use one of the following headings according to the available evidence:

- "Possible Cause"
- "Investigation Notes"
- "Confirmed Cause"

Use "Confirmed Cause" only when the evidence supports the conclusion.

A visual layout overlap confirmed using Android Developer Options may be reported as a confirmed observation.

However, such evidence alone may not establish the exact mechanism of touch-event interception.

Technical explanations must not claim more than the available evidence supports.

### 5.7 Proposed Fix

Include only when a plausible solution is known or the requester explicitly proposes one.

Treat proposed fixes as recommendations unless a specific implementation is required.

### 5.8 Environment

Use precise version information when available.

Example:

- OS: One UI 7.0 (Android 15)
- Device: Samsung Galaxy S21 Ultra
- Keyboard Version: "TBD"
- Commit: "TBD"

Prefer specifying both the manufacturer's operating system version and the underlying Android version.

### 5.9 Acceptance Criteria

Define conditions demonstrating that the malfunction has been resolved.

Criteria should cover:

- The original failure no longer occurs.
- The expected behavior is restored.
- Relevant existing behavior remains functional.
- Important regression scenarios are covered.

Do not mark criteria as completed before verification.

## 6. Feature Request Structure

### 6.1 Description

Describe:

- The proposed capability.
- Its intended purpose.
- Its relationship to existing functionality.
- The expected user benefit.

Avoid unnecessarily long introductions.

### 6.2 Proposed Features

Divide complex functionality into logically independent subsections.

For each subsection, describe:

- The behavior to implement.
- Relevant configuration options.
- User interactions.
- State transitions, if applicable.
- Relationships with existing components.

### 6.3 Expected Behavior

Explain how users will interact with the feature.

Use numbered workflows when interaction order matters.

Use tables when describing multiple states, modes, or configurations.

Examples should be clearly identified as examples rather than mandatory configurations.

### 6.4 Initial Scope

When the feature is intended for incremental implementation, explicitly define the initial scope.

Distinguish:

- Required functionality for the current phase.
- Planned functionality for later phases.
- Optional future extensions.

Do not include future-phase requirements in the current phase's acceptance criteria.

### 6.5 Acceptance Criteria

Each criterion should describe a verifiable outcome.

Use Markdown checkboxes:

```markdown
- [ ] <criterion>
```

Criteria should:

- Be independently understandable.
- Be objectively testable.
- Cover all explicitly requested functionality.
- Avoid prescribing unnecessary implementation details.
- Avoid duplicating the same requirement under different wording.
- Remain unchecked until verified.

### 6.6 Implementation Notes

Include technical recommendations when they meaningfully improve implementation quality.

Examples:

- Reuse an existing repeat mechanism.
- Share layout implementation between the IME service and settings preview.
- Read engine status from the actual state source.
- Persist state snapshots when recovery after process termination is required.

Do not prescribe specific classes, libraries, or architecture without sufficient justification.

## 7. IPA Board-Specific Requirements

### 7.1 Respect Existing Key Customization

IPA Board supports independently configurable tap and long-press actions.

Never assume that a key's tap and long-press behaviors must be identical.

New assignable functions should integrate with the existing customization system.

Do not introduce mandatory bindings unless explicitly requested.

For example, Continuous Cursor Left is a separate function from the existing single-step Cursor Left.

A possible configuration is:

- Tap: Cursor Left.
- Long Press: Continuous Cursor Left.

This is an example, not a mandatory binding.

### 7.2 Reuse Existing Functionality

Before proposing a new implementation, determine whether an equivalent or related mechanism already exists.

Examples:

- Continuous cursor movement should preferably reuse existing directional movement operations and the Continuous Delete repeat mechanism.
- New function keys should use the existing key assignment system.
- New preview interfaces should follow established management interface conventions.

Do not duplicate existing functionality unnecessarily.

### 7.3 Distinguish Keyboard Pages and Page Groups

Use the correct terminology:

- **Keyboard Page:** An individual keyboard page.
- **Keyboard Page Group:** A group of keyboard pages.

Configuration scope must match the request.

For example:

- Key preview popup colors may be configured per keyboard page group.
- Engine requirements may be configured per individual keyboard page.

Do not treat these configuration scopes as interchangeable.

### 7.4 Preserve Input Engine Independence

IPA Board supports multiple input engines, including:

- Rime
- Mozc
- LatinIME
- IPA Engine

When an issue affects multiple engines, distinguish shared service behavior from engine-specific behavior.

Do not attribute a failure to a particular engine without evidence.

### 7.5 Distinguish Engine Configuration and Runtime State

An engine being enabled in configuration does not necessarily mean it is currently running.

When relevant, distinguish:

- Configured enabled/disabled state.
- Actual runtime state.
- Initialization state.
- Temporary state overrides.

Diagnostic interfaces should reflect actual state information rather than assumed values.

### 7.6 Preserve State Restoration Semantics

When a feature temporarily changes engine states, specify the intended restoration behavior.

Do not assume that restoration means enabling all engines.

If exact restoration is requested, preserve the original state of each engine.

Consider persistence across unexpected process termination when explicitly required.

### 7.7 Preserve Shared UI Consistency

When a preview must match a runtime UI component, prefer a shared layout implementation.

Do not maintain separate hardcoded layouts when the request requires automatic synchronization.

Sharing an implementation does not necessarily mean sharing the same Android View instance.

### 7.8 Avoid Unrequested Default UI Changes

If a new function is intended to be user-assignable without a default entry point, preserve that requirement.

Do not automatically add the function to the default keyboard layout.

## 8. Acceptance Criteria Standards

### 8.1 Observable Outcomes

Acceptance criteria must focus on results rather than vague implementation intentions.

Weak:

- [ ] Improve engine management.

Better:

- [ ] Switching to a keyboard page enables engines explicitly configured as Enabled.
- [ ] Engines configured as Unchanged retain their current states.

### 8.2 Positive and Negative Conditions

Where relevant, cover both what should happen and what must not happen.

Examples:

- The dashboard displays current engine states.
- Opening the dashboard does not unexpectedly change engine states.
- Continuous cursor movement stops when the triggering interaction ends.
- Cursor movement does not unintentionally modify text content.

### 8.3 Boundary Conditions

Include relevant boundary conditions without inventing excessive requirements.

Examples:

- Cursor movement at text boundaries.
- Reopening an existing input field.
- Restoring engine states after an unexpected termination.
- Switching between keyboard pages with conflicting engine requirements.

### 8.4 Regression Protection

When modifying existing functionality, include at least one criterion protecting related behavior if a meaningful regression risk exists.

### 8.5 No Premature Completion

All newly created acceptance criteria must initially remain unchecked unless the request explicitly concerns already completed and verified work.

## 9. Technical Reasoning Standards

### 9.1 Evidence Levels

Classify technical statements as:

1. Observed behavior.
2. Reproducible behavior.
3. Confirmed technical findings.
4. Plausible hypotheses.
5. Suggested implementations.

Do not present a hypothesis as a confirmed finding.

### 9.2 Avoid Premature Architectural Commitments

Prefer describing required behavior over selecting an implementation prematurely.

For example, an Issue may require a keyboard preview to remain synchronized with the runtime layout without mandating a particular UI framework.

### 9.3 Identify Relevant Dependencies

Mention dependencies between features when they may affect implementation.

Examples:

- Remote Keyboard and per-page engine requirements may both modify engine states.
- Engine Dashboard may need access to a shared engine state manager.
- Shortcut Management previews may depend on runtime UI layout components.

Do not expand the Issue to implement all related features unless requested.

### 9.4 Respect Platform Limitations

Do not promise Android behavior that has not been verified.

InputMethodService behavior, InputConnection compatibility, and application-specific input handling may vary.

When appropriate, identify compatibility as a testing requirement rather than a guaranteed property.

## 10. Formatting Standards

Use GitHub Flavored Markdown.

### 10.1 Headings

Use:

- `##` for primary sections.
- `###` for subsections.

Avoid excessive heading depth.

### 10.2 Lists

Use numbered lists for ordered procedures.

Use bullet lists for independent requirements.

Use task lists for acceptance criteria.

### 10.3 Tables

Use tables for:

- State transitions.
- Compatibility matrices.
- Engine configurations.
- Environment comparisons.
- Other structured comparisons.

Avoid tables when ordinary prose is clearer.

### 10.4 Code Formatting

Use inline code for:

- Class and method names.
- Configuration identifiers.
- Branch names.
- Commit hashes.
- Android constants.
- Exact UI labels when appropriate.

Use fenced code blocks for longer code examples.

### 10.5 Writing Style

Prefer:

- Short, direct sentences.
- Consistent terminology.
- Active voice.
- Specific technical descriptions.
- Minimal repetition.

Avoid:

- Promotional language.
- Unnecessary speculation.
- Unsupported claims.
- Redundant acceptance criteria.
- Excessively detailed implementation instructions.

## 11. GitHub Metadata

Use the following defaults unless the requester specifies otherwise.

| Field | Default |
| --- | --- |
| Assignee | Repository owner, when appropriate |
| Bug Label | `bug` |
| Feature Label | `enhancement` |
| Project | Unassigned |
| Milestone | Unassigned |
| Priority | Based on impact and urgency |
| Issue Number | Automatically assigned by GitHub |

### 11.1 Priority

Suggested guidelines:

**High**

- Core functionality is significantly impaired.
- A major operation consistently fails.
- No reasonable workaround exists, or the workaround is disruptive.

**Medium**

- Functionality is impaired but usable.
- An important usability improvement is needed.
- A planned feature has meaningful development value.

**Low**

- Minor visual issues.
- Noncritical usability improvements.
- Cosmetic inconsistencies.

Do not assign high priority merely because an Issue contains many requirements.

Priority labels should only be used if the repository has adopted them.

### 11.2 Avoid Unnecessary Metadata

Do not create Projects, Milestones, or additional labels without a clear purpose.

## 12. Relationship Between Issues, Branches, PRs, and Releases

### 12.1 Issues

Issues describe problems, requirements, and acceptance criteria.

### 12.2 Branches

Branches isolate implementation work.

Use the branch name generated by GitHub's issue development workflow by default, typically `<issue-number>-<issue-title-slug>`. A custom name is optional, not required for every issue.

- Create each branch from the current target development branch, such as `0.0.3-dev`, and explicitly set the PR base to that branch.
- An issue may span multiple PRs, including multiple PRs within the same version. Each PR should have a coherent, verifiable scope.
- After the previous PR is merged and its remote and local working branches are cleaned up, the same branch name may be reused for later work on that issue. Create it afresh from the current target base; do not resume or restore the old branch.
- Reuse the original name explicitly if the issue title has changed. Add a short phase or version suffix only when an existing branch, parallel work, or another concrete ambiguity requires it.
- Identify historical work by PR number and commit, since a reused branch name points to the latest work rather than a permanent historical revision.

Branch names are separate from Issue titles.

### 12.3 Commits

Commits document individual code changes.

Conventional Commits are recommended.

Examples:

- `fix(ime): restore candidate suggestions`
- `feat(ui): add engine dashboard`
- `refactor(ui): share multifunction bar layout`

### 12.4 Pull Requests

PRs document implementation and verification.

Recommended sections:

- Summary
- Changes
- Testing
- Related Issue

Use references such as `Refs #12` when automatic closure is not intended.

For phased work, state the phase completed by this PR and the scope still outstanding. Completing one phase does not complete the entire issue.

Use closing keywords such as `Closes #12` when appropriate for the repository's merge workflow.

GitHub's automatic Issue closure behavior depends on the target branch and repository default branch.

### 12.5 Issue Closure

Close an Issue only when its acceptance criteria have been satisfied or when an explicit decision has been made not to proceed.

A merged PR does not automatically prove that all requirements have been verified.

### 12.6 Changelog and Release Notes

Record user-visible changes in `CHANGELOG.md`.

Use the appropriate category:

- Added
- Changed
- Fixed
- Removed
- Deprecated
- Security

Do not list unimplemented features as completed changes.

Reference relevant Issue or PR numbers when useful.

## 13. Automation Agent Workflow

An AI agent generating an Issue should follow this sequence.

### Step 1 — Understand the Request

Identify:

- The requested change.
- The affected component.
- Existing related functionality.
- The user's intended outcome.
- Explicit restrictions.
- Initial implementation scope.
- Relevant acceptance conditions.

### Step 2 — Resolve Essential Ambiguities

Ask for clarification only when ambiguity would materially change the requirements.

Do not ask questions merely to fill optional sections.

When the request is sufficiently clear, proceed directly.

### Step 3 — Classify the Issue

Determine whether the request is a Bug, Feature, or another category.

### Step 4 — Generate the Title

Create a concise, technically accurate title using the repository's established convention.

### Step 5 — Generate the Description

Use the appropriate Issue structure.

Include only sections that materially improve clarity.

### Step 6 — Validate Requirements

Before submission, verify:

- No requested behavior has been omitted.
- No unrequested behavior has been added as mandatory.
- Existing functionality has not been misrepresented.
- Examples are not confused with requirements.
- Hypotheses are not presented as facts.
- Acceptance criteria match the requested scope.
- Technical recommendations are appropriately qualified.
- Terminology is consistent.

### Step 7 — Assign Metadata

Apply existing repository labels and other metadata according to the established conventions.

Do not create new labels or modify repository settings without authorization.

### Step 8 — Present or Submit

When drafting, provide a copy-ready title and complete Markdown description.

When authorized to create an Issue through GitHub integration, submit the validated content.

Do not claim that an Issue was created unless the submission succeeded.

### Step 9 — Verify Submission

After successful submission, confirm:

- The Issue URL and number.
- The title and description.
- The Issue type and labels.
- The assignee, if applicable.
- Any explicitly requested relationships.

Report any failed or incomplete operation.

## 14. Final Quality Checklist

Before finalizing an Issue, the author or automation agent must verify:

**Accuracy**

- [ ] The Issue reflects the original request.
- [ ] No unsupported facts were introduced.
- [ ] Existing functionality is described accurately.
- [ ] Confirmed findings and hypotheses are clearly distinguished.

**Scope**

- [ ] The implementation scope is explicit.
- [ ] Optional features remain optional.
- [ ] Future extensions are separated from current requirements.
- [ ] No unrelated features were added.

**Structure**

- [ ] The title is concise and descriptive.
- [ ] The Issue type is appropriate.
- [ ] The description explains the problem or feature.
- [ ] Expected behavior is clear.
- [ ] Acceptance criteria are testable.
- [ ] Markdown formatting is consistent.

**Technical Quality**

- [ ] Relevant existing mechanisms are considered.
- [ ] Implementation suggestions do not unnecessarily restrict the solution.
- [ ] Important state transitions are documented when applicable.
- [ ] Relevant regression risks are covered.

**GitHub Integration**

- [ ] Appropriate existing labels are selected.
- [ ] Issue numbering is left to GitHub.
- [ ] Related Issues or PRs are referenced correctly.
- [ ] Submission status is verified before reporting success.

## 15. Core Rule

Describe the requested outcome precisely, preserve existing behavior unless explicitly changed, and never turn assumptions into requirements.

The purpose of an Issue is to establish a reliable development contract between the requester and the implementer.

A high-quality Issue should remain understandable, actionable, and verifiable even when reviewed months after its creation.
