# Project Requirements - MyFiles App

## 1. Core Functionality
- The app is an Android File Manager starting at the Internal Storage root (`Environment.getExternalStorageDirectory()`).
- Supports folder navigation and hierarchical back navigation.
- Handles storage permissions across different Android versions (Legacy and Scoped Storage/Manage All Files).

## 2. Scroll Position Restoration (CRITICAL)
- The app MUST remember the scroll position (index and offset) for every directory path visited.
- When navigating back to a parent folder or re-entering a previously visited folder, the scroll position must be restored exactly where the user left it.
- This is achieved by capturing `LazyListState.firstVisibleItemIndex` and `firstVisibleItemScrollOffset` before navigating away and storing them in a `scrollPositions` map keyed by path.
- This behavior is a core requirement and should NOT be changed or violated.

## 3. UI Layout & Item Display
- **Icon**: Leftmost side. Icon size must be proportional to the filename font size (calculated as `(nameFontSize * 1.5).dp`) to prevent wasting vertical space when fonts are small.
- **Filename**: Next to the icon. Must wrap to multiple lines if too long.
- **Right-Aligned Info**:
    - Folders: Item count (e.g., "10 items") must be on the right-most side.
    - Files: File size and Last Modified date must be on the right-most side, stacked vertically.
- **Layout Constraints**: Long filenames must NOT push the right-aligned information off-screen. This is managed using `Modifier.weight(1f, fill = false)` on the filename `Text` within a `Row` using `Arrangement.SpaceBetween`.
- **Dynamic Height**: Row height must be dynamic. It should remain sleek for single-line names and expand vertically only when a name wraps, while respecting the user's `itemSpacing` setting.

## 4. Settings & Persistence
- The following settings must be supported and persisted via `SharedPreferences`:
    - **Name Font Size**: Controls filename size and proportionally scales icons.
    - **Info Font Size**: Controls size of item counts, file sizes, and dates.
    - **Folder Icon Color**: Customizable color for folder icons.
    - **Theme Mode**: (System/Light/Dark).
    - **Item Spacing**: Vertical padding between rows.
    - **Show/Hide Dividers**: Toggles `HorizontalDivider` between items.
- Settings must apply immediately in the UI upon change.
- Settings must be loaded and applied correctly when the app starts.

## 5. Navigation & UX
- **Back Handler**:
    - If Settings screen is open, the back button MUST close the Settings and return to the file browser.
    - Otherwise, it navigates to the parent folder and restores its scroll position.
- **Header**: The top bar displays the current directory's absolute path and a settings gear icon.
