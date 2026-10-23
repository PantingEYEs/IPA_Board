# [Feature] Add Popup Multifunctional Actions with a graphical editor

Submitted as [Issue #20](https://github.com/PantingEYEs/IPA_Board/issues/20) on 2026-10-24. This local copy includes the requester-confirmed gesture definitions and graphical editor requirements.

## Description

Add Popup Multifunctional Actions as an alternative to the existing long-press and quick horizontal swipe actions. Each keyboard key independently selects Classic or Popup mode. Popup mode allows a user to hold a keyboard key, slide the same finger over selectable circular items in its preview popup, and interact with a selected item through the existing long-press key behavior. Action execution must be shared with keyboard keys and fixed toolbar entries; the gesture equivalences below define Popup behavior.

Provide a graphical editor inside the existing keyboard-key settings. Users can edit the popup boundary, its position relative to the keyboard key, and the position, size, binding, and stacking layer of its items.

## Terminology and Scope

| Term | Meaning |
| --- | --- |
| Keyboard key | The physical touch target on a keyboard page whose settings are being edited. |
| Multifunctional Actions | The additional interactions beyond the keyboard key's ordinary tap binding. |
| Classic mode | The existing long press, quick swipe left, and quick swipe right interactions. |
| Popup mode | The new hold-and-slide interaction, replacing all three Classic interactions while selected. |
| Popup | The editable four-vertex boundary used for the new selection interface. |
| Popup item | A circular, icon-bearing selectable object inside the popup system. It is distinct from the keyboard key. |
| Reference point | The keyboard key's center expressed relative to the popup; this relationship determines the popup's runtime position. |
| Viewport | The rectangular editing view through which the popup, reference point, and items are manipulated. |

Popup items and keyboard keys must support the same binding capabilities. Do not silently restrict the popup to a subset of assignable functions. The existing long-press implementation is the behavioral authority for every binding under the equivalences below.

This draft covers the complete requested feature. No reduced initial phase has been approved.

## Shared Action Execution and Behavioral Priority

- Keyboard keys, fixed multifunction-bar entries, and Popup items must call the same execution implementation for the same function. A future change to that function must not require separate behavioral implementations for each entry point.
- Input surfaces may use different gesture recognition and presentation code. Popup interaction must map to the existing long-press key implementation according to the definitions below, rather than define new behavior for individual functions.
- Maintaining these equivalences takes precedence over the particular behavior of any bound function. This applies equally to a keyboard long-press key and a Popup item.
- During work on this issue, use the existing long-press implementation as the authority; do not modify function-key logic to make it conform to a newly designed Popup interaction.
- If an inherited behavior proves undesirable, address it later by adjusting the long-press and Popup behavior together. It is not a prerequisite or separate function redesign within this issue.
- Preserve existing keyboard and toolbar behavior while connecting Popup items to the shared execution path. This does not require adding a default toolbar entry for every action.

## Normative Gesture Equivalences

In the table, the Popup-side item and Classic-side long-press key have already been activated. "Held" means the controlling finger has not been lifted. The keyboard key in return-to-key gestures is the originating keyboard-page key.

| Definition | Popup interaction | Equivalent existing long-press key interaction |
| --- | --- | --- |
| 1a | Activate a Popup item and remain on it while held. | Activate a long-press key and remain on it while held. |
| 1b | Activate a Popup item and release on it. | Activate a long-press key and release on it. |
| 2a | After activation, leave the item but remain inside the popup while held. | After long-press activation, slide horizontally left or right while held. |
| 2b | After activation, leave the item and release inside the popup. | After long-press activation, slide horizontally left or right and release. |
| 3a | After activation, leave the item and move outside the popup while held. | After long-press activation, slide downward while held. |
| 3b | After activation, leave the item and release outside the popup. | After long-press activation, slide downward and release. |
| 4a | After activation, move outside the popup, then return to the keyboard key without lifting. | After long-press activation, slide downward, then return to the keyboard key without lifting. |
| 4b | After activation, move outside the popup, then return to and release on the keyboard key. | After long-press activation, slide downward, then return to and release on the keyboard key. |

**Definition 5 — precedence and scope:** These equivalences always take priority over function-specific expectations. For this issue, preserve the current long-press function logic and apply it to Popup items. Any later behavioral revision must keep both interaction forms aligned.

## Per-key Mode Selection and Configuration Retention

- Select Classic or Popup independently for each keyboard key in its existing settings.
- Classic is the default mode for existing configurations.
- Switching to Popup replaces the Classic long-press and left/right quick-swipe editing areas with the graphical popup editor. Keep this inside the existing key settings rather than introducing a separate settings page.
- While Popup is selected, do not run the stored Classic multifunctional bindings. Conversely, Classic mode does not run the stored Popup configuration.
- Preserve both configurations when switching modes, including across saving and reopening settings. Switching Classic → Popup → Classic must restore the original Classic bindings; switching the other way must retain the edited Popup configuration.
- Do not erase or reinterpret existing tap or Classic bindings during migration or mode changes. Ordinary tap interaction remains distinct from the selected Multifunctional Actions mode.

## Runtime Interaction

1. Long-press a keyboard key configured for Popup mode to display its popup. Slide the same finger over Popup items to select freely.
2. Invert an item's colors while the controlling touch is within its active region. Remove that inversion when the pointer leaves the item. Where items overlap, only the topmost item is active.
3. Activation, remaining on an item, and release on it follow definition 1. Do not impose a blanket rule that every function executes only on release.
4. Leaving an activated item while staying inside the popup follows definition 2, including when the pointer is over empty space. Empty space inside the popup is not an outside-cancellation region; do not impose a separate empty-space release rule.
5. Leaving the popup cancels immediately when the pointer crosses outside, including while the finger is still held. Do not wait for an outside release. This corresponds to the existing downward-slide cancellation in definition 3.
6. After this cancellation, returning to an item, the popup, or the originating keyboard key does not reactivate the same gesture. A subsequent release must not produce a new activation or tap. Follow the existing canceled-downward-slide state through both return-to-key cases in definition 4.
7. Cancellation has the effects of the existing long-press implementation. This issue does not introduce new function-specific undo, recovery, or alternative execution semantics.

The mappings apply after item activation; the original holding finger's initial position before entering the popup must not be mistaken for having left it. The geometry and hit-testing rules determine which item is targeted, while the equivalences determine how its function behaves.

### Runtime Screen Clipping

- Preserve the configured runtime position and size. Clip portions beyond the usable screen instead of automatically translating or shrinking the popup and its items.
- Keep the saved configuration unchanged. Screen clipping does not replace the popup-boundary overlap rule used when filtering items on Save.
- Editor panning and off-viewport objects remain supported independently of runtime screen clipping.

## Graphical Editor

### Viewport Navigation

- With no object selected, allow free viewport panning. Objects may extend beyond the visible viewport.
- Double-tap empty viewport space to reset its position so that the viewport center aligns with the reference point.
- Single-tap empty viewport space to deselect any selected object.
- Selecting another object deselects the previously selected object.

Panning changes the editing view, not the saved positions of its objects. Use the fixed object order described below for selection; circular items take precedence over the reference point and popup.

### Popup Selection and Shape Editing

- Tap the popup object to select it, show its vertices, and show shape options below the viewport.
- Provide `Uniform`, `Freeform`, `Distort`, and `Rotate` options.
- Selecting an option changes the constraints applied to subsequent vertex editing; it must not immediately change the existing shape.
- Drag vertices to edit the boundary. Only the bottom-right vertex is editable in modes other than `Distort`; all four vertices are editable in `Distort`.
- Do not provide vertex-count editing in this feature.
- Double-tap the popup object to regularize its boundary into a square. The square-construction algorithm is left to the implementation; no particular size, orientation, or placement formula is required. This is a boundary edit, not a full configuration reset.

The confirmed constraints are:

| Option | Constraint |
| --- | --- |
| `Uniform` | Proportionally scale the current outline, preserving its aspect ratio and any existing distortion. |
| `Freeform` | Independently scale the current outline horizontally and vertically. Preserve existing distortion; an initially rectangular outline remains rectangular. Do not regularize it into a rectangle. |
| `Distort` | Move any of the four vertices independently; concave and self-intersecting quadrilaterals are allowed. |
| `Rotate` | Rotate about the mean of the four vertex coordinates, not the reference point. |

Resizing, distorting, rotating, or regularizing to a square changes only the popup boundary. Do not move or resize circular items, their icons, or the reference point as a side effect. Switching constraints still must not immediately reshape the object. Do not silently prohibit self-intersection as an implementation shortcut.

### Reference Point

- Reuse the coordinate-line and crosshair appearance of Pointer Location for the reference point.
- Tap the reference point to select it and show its coordinate lines.
- Tap it again, or select another object, to deselect it.
- Drag the selected reference point to change its position, including entirely outside the popup.
- Double-tap it to move it to the popup's centroid.

The reference point corresponds to the keyboard key's center. Double-tap centering uses the area centroid of the even-odd filled region, including for self-intersecting outlines. If its area is zero, use the mean of the four vertex coordinates instead.

### Popup Items

| Interaction | Required behavior |
| --- | --- |
| Tap an existing item | Select it and invert its colors; deselect other objects. |
| Tap the selected item again | Open editing for its binding and stacking layer. |
| Drag the selected item | Change its position. Allow dragging beyond the popup boundary. |
| Pinch the selected item | Resize its circular region and icon together. |
| Quickly double-tap the selected item | Delete it directly. |

Use the binding's existing function symbol or bound text as the item icon. Keep `∅` for an unconfigured item.

### Stacking and Object Selection

- Use a strict linear order with no shared layers.
- The popup is always the bottom object. The reference point is immediately above it. All circular items occupy the remaining positions above the reference point.
- When objects overlap, select circular items before the reference point or popup, and use the topmost circular item where several items overlap.
- Edit item stacking through `Bring to Front`, `Move Up`, and `Move Down`; do not require users to assign numeric layer values. Items cannot move below the reference point or popup.
- Vertex handles belong to the popup's editing controls rather than additional independently layered objects.

### Single- and Double-tap Arbitration

The requester accepts waiting for the system's double-tap decision before executing a conflicting single-tap action. In particular, double-tap deletion must not first open the item editor, and reference-point centering must not first deselect that point.

## Controls Outside the Viewport

| Control | Required behavior |
| --- | --- |
| Add Item | Create a default-size item at the reference point. Its icon is `∅` until a binding is configured. |
| Undo | Undo editing operations. |
| Redo | Redo undone editing operations. |
| Cancel | Discard this session's unsaved edits, exit key settings, and clear the session's editing history. Show an Undo affordance. System Back while configuring Popup mode follows the same cancellation behavior. |
| Save | Remove unconfigured items and items whose complete circular region is outside the popup. Retain configured items whose circular region overlaps it at least partly. Save the configuration, exit key settings, clear the session's editing history, and show an Undo affordance. |
| Reset | Initialize this keyboard key's Popup Multifunctional Actions configuration and show an Undo affordance. |

An item must be both configured and geometrically eligible to survive Save. Merely placing its center outside the popup is not sufficient reason to delete it if part of its circle still overlaps the popup.

### Initial State and Reset

- Derive the initial popup shape, size, and reference position from the Classic key-preview popup. Do not introduce an unrelated initial geometry.
- Start with an empty popup containing no items. Add Item creates an item at the reference point; its icon remains `∅` until configured.
- Reset initializes this keyboard key's Popup configuration to that initial state. It does not erase the ordinary tap binding or retained Classic configuration.

### Editing History and Operation-level Undo

Keep ordinary editing Undo/Redo separate from the approved one-time recovery snapshot offered after an operation:

| Operation | One-time Undo result |
| --- | --- |
| Cancel, including system Back | Reopen key settings and restore the discarded unsaved draft. |
| Save | Restore the saved key configuration from before this Save. Save itself exits key settings. |
| Reset | Restore the draft from before Reset. |

Clearing the editing history on Cancel or Save must not remove this operation-level recovery snapshot. The snapshot restores the specified state, rather than reconstructing the cleared sequence of editing steps.

## Geometry and Region Definitions

The requester has confirmed the following definitions:

- Use the even-odd filled region for drawing, inside/outside tests, and popup–item overlap calculations. A self-intersecting outline must not be rejected merely because its edges cross.
- Define the rotation center as the mean of the four vertex coordinates.
- Define reference-point double-tap centering by the filled region's area centroid; for zero area, fall back to the mean of the four vertices.
- Switching among `Uniform`, `Freeform`, `Distort`, and `Rotate` does not immediately change geometry. Subsequent Uniform/Freeform manipulation scales the current outline rather than removing its existing distortion.
- Double-tapping the popup object is the separate, explicit way to regularize the boundary into a square. Its exact construction algorithm is implementation-defined.

Use these same geometric results for the editor, runtime display, hit testing, and Save filtering. Apply runtime screen clipping without modifying the stored geometry.

## Implementation Considerations

Code review found an implementation path but did not establish device compatibility or prove the runtime interaction through a prototype. No feature tests have been run for this proposal. The architectural options below are recommendations; they do not replace the confirmed interaction and geometry requirements above.

- Existing previews are non-focusable, non-touchable windows while the original keyboard key tracks the gesture. Keeping that gesture owner and mapping its pointer coordinates into the popup could support sliding selection without transferring the touch stream or stealing editor focus. This is an implementation option, not a required architecture. See [Android popup behavior](https://developer.android.com/reference/android/widget/PopupWindow).
- For a rectangular window containing a nonrectangular popup, custom drawing and geometric hit testing could represent the requested boundary and circles. If partially protruding circles must remain visible, a transparent window covering their combined bounds is an option; its outside regions must not accidentally become selectable.
- If separate-window behavior is unreliable, evaluate an IME-owned overlay using the same geometry and gesture rules. Rendering outside the keyboard region would still require device validation. Do not silently substitute a popup restricted to the keyboard area.
- For ambiguous taps, [confirmed single-tap detection](https://developer.android.com/reference/android/view/GestureDetector.OnDoubleTapListener) supports the requester-approved delayed-single-tap behavior.
- Concave and self-intersecting shapes are explicitly permitted. Use the confirmed even-odd [path fill rule](https://developer.android.com/reference/android/graphics/Path.FillType) consistently for drawing, hit testing, and Save filtering; do not fall back to rejecting these shapes.
- For off-screen content, retain the configured position and clip to the usable screen as requested. Do not introduce fit-to-screen translation or scaling as a fallback. Test visible regions, clipping, and release cancellation without rewriting saved coordinates.

A linear item order can be represented by an ordered collection; no particular library, data structure, or benchmark target is mandated. No performance measurements have been made for this proposal.

## Acceptance Criteria

All criteria below are unverified implementation targets. The five normative definitions above override any earlier function-specific interaction proposals.

- [ ] Each keyboard key selects Classic or Popup independently. Existing layouts default to Classic, and both configurations survive mode switching, saving, reopening, and layout export/import.
- [ ] Popup mode substitutes its interaction and editing controls for the three Classic multifunctional interactions without deleting their stored bindings or changing the ordinary tap binding.
- [ ] All eight held/released cases in definitions 1–4 match their existing long-press equivalents for the same binding. No function receives Popup-specific execution semantics.
- [ ] Moving outside the popup cancels before release; returning while held or returning and releasing does not reactivate the canceled gesture or produce an unintended tap.
- [ ] Empty-space movement and release inside the popup follow the existing horizontal-slide long-press behavior rather than a separate cancellation rule.
- [ ] The existing key settings contain the graphical viewport, exclusive object selection, empty-space deselection, panning, and reference-centered viewport reset.
- [ ] All four shape options work without an immediate shape change on selection. Uniform and Freeform retain existing distortion; Distort supports concave/self-intersecting shapes; Rotate uses the four-vertex mean. Permitted handles remain correct and vertex count stays fixed. Boundary changes do not transform items or the reference point.
- [ ] Double-tapping the popup boundary object regularizes it to a square without resetting its items or reference point; editing Undo/Redo covers this geometry edit.
- [ ] Reference-point selection, coordinate-line feedback, and dragging outside the popup work. Double-tap centers it on the even-odd filled-region centroid, falling back to the four-vertex mean for zero area.
- [ ] Popup items use binding symbols/text, support dragging and joint region/icon scaling, and use strict item order above the reference point and popup. Bring to Front/Move Up/Move Down cannot create tied layers or move items into the two fixed bottom positions.
- [ ] Confirmed single-tap handling prevents conflicting item-edit/reference-deselect effects during double-tap deletion/centering, and popup double-tap square regularization is distinguished from empty-viewport double-tap reset.
- [ ] Initial/reset geometry follows the Classic preview, starts with no items, and references the keyboard key center. Add Item places an unconfigured `∅` item at the reference point.
- [ ] Editing Undo/Redo, Cancel, Save-and-exit, Reset, system-Back cancellation, and all three approved operation-level Undo snapshots restore the specified states without erasing retained Classic/tap settings.
- [ ] Save removes unconfigured items and wholly nonoverlapping circles, while preserving configured partially overlapping items according to the agreed boundary rule.
- [ ] Runtime preserves configured position and size while clipping off-screen content; it does not automatically translate, shrink, or rewrite the saved popup configuration.
- [ ] Keyboard keys, fixed toolbar entries, and Popup items invoke the same implementation for the same function; Popup adapts its gestures to existing long-press logic without redesigning bound functions.
- [ ] Regression coverage protects Classic gestures and independent tap bindings, both retained configurations, geometry and layer selection, editor history, save filtering, the eight equivalence cases, and cancellation without unintended reactivation.

## Validation Plan

- Host tests: shared execution and the complete gesture-equivalence table; configuration retention and legacy migration; proportional and independent outline scaling without lost distortion; even-odd regions, vertex-mean rotation, and centroid/zero-area fallback; square regularization; overlap and stacking; save filtering; editing and operation-level history.
- Owned-view tests: graphical selection, vertex constraints, panning, selected-item pinch, single/double-tap arbitration including popup square regularization versus viewport reset, mode-dependent controls, and system Back.
- Behavioral comparison tests: for the same binding, compare Popup input sequences with the corresponding existing long-press sequences in all eight held/released cases. Verify intermediate behavior while held, not only the final release. Cover immediate outside cancellation, inside movement/empty space, and both return-to-key cases. Treat the existing long-press behavior as the baseline; do not change its expectations to fit Popup behavior.
- Real IME tests: the original pointer leaving its keyboard key and entering the popup, preserved-position screen-edge clipping, equivalent function execution, immediate cancellation, return after cancellation, and editor-focus/composing-state behavior consistent with the corresponding long-press interaction. Directly dispatched events alone do not establish cross-window behavior.
- Run device regression only through the [backup and rollback workflow](../testing.md). Do not change other applications, system clipboard contents, or snapshot scope for this feature without the required authorization.
