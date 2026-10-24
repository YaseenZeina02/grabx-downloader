# Interface languages

Settings > Language offers English, العربية, Español, Français, Deutsch and Türkçe.
The selector is only in Settings. It uses the existing draft/save/discard flow and
persists grabx.language in the controller's preference node. Unsupported saved codes
fall back to English. Saving immediately updates the active interface and direction without interrupting
downloads or restarting the application. Discard keeps the current interface unchanged. The browser extension remains independent.

The desktop presentation catalog is messages.json (216 entries per translated
language). English UI strings are catalog keys; missing entries and external server
or tool messages retain their source text. Download states, mode/quality identifiers,
list selections, URLs, paths and user input remain unchanged. ComboBox cells translate
only the display value, not its underlying item. Bound status labels map presentation
text explicitly; translation observers never unbind or overwrite bound model data.

Window/child observers cover newly opened dialogs, tooltips, menus, settings sections
and list cells. The saved language applies at launch and also immediately after Save. Weakly tracked
source strings support reversible switching on existing controls and detached library
content; observable locale bindings refresh active status labels without mutating
model data. Arabic mirrors the root layout and loads
the bundled Cairo font. Input fields keep left-to-right orientation for URLs and paths.
Numeric units remain standard KB/s, MB and GB; no global Java locale or extractor options
change. Global speed/date parsing and transfer filenames are unaffected.

Verification includes saved-code fallback, multilingual presentation and untouched
URL/path strings, language draft save/discard, Arabic input direction, and all six
languages fitting the settings action bar at the 900x500 minimum. A same-window English→Arabic→English Save regression verifies immediate updates and
unchanged status/URL model values. Visual Arabic
settings preview is generated offline without opening real downloads or preferences.

Scene root replacements (including the delayed hover overlay) are observed, so
live language changes update the actual root orientation after wrapping.
