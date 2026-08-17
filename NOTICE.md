# Third-party notices

Squid Utils itself is MIT-licensed (see `LICENSE`). It bundles one
third-party library directly into the built jar, which carries its own,
different license:

## MoulConfig

`mod/build.gradle.kts` shades [MoulConfig](https://github.com/NotEnoughUpdates/MoulConfig)
(the settings GUI library) into the mod's jar via `include(...)`, rather than
requiring it as a separate download.

MoulConfig is licensed under the **GNU Lesser General Public License v3.0 or
later (LGPL-3.0-or-later)**, copyright NotEnoughUpdates contributors. Its
source is available at the link above; the full license text is at
<https://www.gnu.org/licenses/lgpl-3.0.html>.
