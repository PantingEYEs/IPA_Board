# Pull Request Writing Guidelines

Use these rules when preparing or revising an IPA Board pull request. They follow the English titles and Summary / Key Changes / verification structure used in [PR #7](https://github.com/PantingEYEs/IPA_Board/pull/7) and [PR #5](https://github.com/PantingEYEs/IPA_Board/pull/5), including requirement mapping where it helps review.

## Storage and file names

- Store all PR drafts and local copies of submitted descriptions in `docs/pull-requests/`. This directory is Git-ignored; do not force-add its contents.
- Keep this shared guideline at `docs/pull-request-guidelines.md`, outside the ignored directory, so it can be committed and read after cloning.
- Before a PR number is assigned, use `issue-<issue-number>-<short-kebab-case-topic>.md`, for example `issue-8-engine-dashboard.md`. For work without an issue, use `draft-<short-kebab-case-topic>.md`.
- Once the PR number is known, rename the same file to `pr-<pr-number>-<short-kebab-case-topic>.md`. Use the actual PR number, not the issue number, and keep one current document per PR.
- Git does not preserve empty directories. Recreate `docs/pull-requests/` locally when preparing a PR; do not add a placeholder inside the ignored directory.

## Language and title

Write the title, headings, tables, and prose in English using GitHub-flavored Markdown. Preserve exact UI labels, code identifiers, and symbols such as `◷` when necessary.

The file's first line is `# <PR title>`. Copy its text to GitHub's title field; the remaining content is the PR body. Do not paste the title heading into the body again.

Use this title format:

```text
<type>(<scope>): <imperative summary>
```

- Choose the type by the main result: `feat`, `fix`, `refactor`, `test`, `docs`, `build`, `ci`, or `chore`.
- Use a short lowercase scope such as `ime`, `ui`, `engines`, `diagnostics`, or `testing`; omit the scope only when the change spans the project without a useful primary area.
- Start the summary with an imperative verb such as `add`, `fix`, or `preserve`. Keep it specific, sentence-initial lowercase, and without a trailing period. Aim for 72 characters; shorten secondary details before making the title longer.
- Describe the final change as a whole. Mention supporting fixes in the body unless they are essential to understanding the title. Do not enumerate commits or use a branch name as the title.
- Put issue closing references in the body. Add `!` and a `BREAKING CHANGE:` explanation only for an actual incompatible change.

Example: `feat(ime): add an engine dashboard with a customizable key`.

## Body structure

Use the following order. `Summary`, `Key Changes`, and `Verification` are required. Omit optional sections when they add no useful information.

| Section | Content |
| --- | --- |
| Issue reference | Put `Closes #<number>` first only when the PR completes that issue. Use `Refs #<number>` for partial or related work. |
| `## Summary` | One short paragraph explaining the concrete problem or need and the resulting behavior. A bug fix should describe its trigger and before/after result. |
| `## Key Changes` | Group the final behavior and implementation by reviewer-relevant topic. Use numbered `###` subsections for several distinct areas; use a short list for a small change. Include root cause and fix together when relevant. |
| `## Acceptance Criteria` (optional) | A compact table mapping the issue's requirements to implementation and verification. Use for a feature with several independent requirements; do not repeat every change or claim untested behavior is verified. |
| `## Verification` | Separate completed checks from pending work. Record actual results, relevant environment, and material limitations. |
| `## Related Commits` (optional) | List actual short SHAs with brief purposes only when they help reviewers navigate a multi-part change. |
| `## Compatibility and Limitations` (optional) | Explain configuration migration, changed behavior, known restrictions, and untested platforms that affect acceptance. |

Lead with behavior; reference files or APIs only to explain a cause, boundary, or design choice. Keep the description proportional to the change. Avoid conversational history, abandoned approaches, raw logs, unrelated future plans, and unsupported performance claims.

## Verification rules

- Use `[x]` only for checks actually completed and passed on the relevant implementation. Use `[ ]` for planned checks, or explicitly say `Not run` with the reason. Prefer `Verification` over labeling completed work as a plan.
- Distinguish compilation, host unit tests, instrumentation, and manual checks. Compiling test code is not evidence that the tests ran; automated UI tests are not manual testing.
- Give relevant test counts, build variant, Android API/ABI, failures/skips, and lint errors or remaining warnings. Link repository verification records or report locations when useful; do not paste full output.
- For device regression, state whether original IPA Board software and private data were restored and verified, and whether the backup was deleted. Restoration failure must remain a failure even when assertions passed. Follow [the testing workflow](testing.md).
- State important untested environments explicitly; a debug ARM64 emulator result does not verify release builds, other APIs/ABIs, or physical devices.
- If a change lands after verification, identify the tested revision or scope accurately and rerun checks when behavior changes. Never mark earlier failed reports as passed; distinguish the final successful run.
- Keep personal configuration, input, clipboard contents, full device logs, and machine-specific private backup paths out of PR text. Use repository-relative report paths or committed documentation links.

## Minimal template

```markdown
# feat(scope): add a specific capability

Closes #<issue-number>

## Summary

Describe the need and resulting behavior in one short paragraph.

## Key Changes

- Describe the main behavior and any necessary implementation detail.
- Describe a related fix or compatibility decision if applicable.

## Verification

- [x] Describe a completed check and its actual result.
- [ ] Describe an outstanding check, if one is required.
```

Replace all placeholders and remove inapplicable lines before submission. Refresh the title, body, and verification whenever the PR's scope changes. Preparing this local document does not create or update a GitHub PR.
