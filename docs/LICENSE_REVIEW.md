# License and attribution

See [NOTICE](../NOTICE) for the project copyright and origin attribution.

## Project license and ownership statement

The owner states that FahrerTagebuch was their own private project from its
beginning, identifies themselves as the owner of the inherited project code,
and authorizes Apache License 2.0 for this extracted library. On that basis, project-owned source,
synthetic project examples/fixtures, documentation and newly authored project SVGs
are offered under the unmodified [Apache License 2.0](../LICENSE).

This records the owner's statement and licensing instruction, not an independent
legal audit of every contribution. The retained source/generators contain no
identified vendored third-party parser. Synthetic fixture provenance is documented
in [TESTING.md](TESTING.md); synthetic construction does not itself prove ownership.
Any subsequently identified third-party contribution must be handled under its
actual rights and terms. No title guarantee for unknown material is asserted.

Existing upstream notices are preserved. The project license does not relicense
external dependencies or the Gradle wrapper. No original ERCA key is included;
application-supplied key material remains subject to its own applicable rights.
New SVGs are original project illustrations with no external images, font files
or scripts embedded. Their font-family names select locally available fonts.

## Redistribution and attribution

Apache-2.0 allows private/commercial use, modification and redistribution subject
to its actual terms. In particular, §4 requires recipients to receive the license,
modified files to be marked, relevant source notices to be preserved, and relevant
NOTICE attribution to remain readable in one of the permitted locations.

The project attribution is:

```text
Driver Card Parser & Android USB Library
Developed by RN3
https://rn3.de
Copyright 2026 RN3
```

Keep the applicable notices when distributing the source or library in a product.
A readable NOTICE, accompanying source/documentation, or the normally used
third-party-notices display can meet the relevant §4(d) placement rule. A discreet
origin entry in accompanying third-party notices referring to https://rn3.de is a
suitable placement. **There is no added requirement to place branding in every
application's UI.** NOTICE is informational and does not alter Apache-2.0. It does not claim ownership of others'
work or grant additional trademark rights. Consult the full license for all terms.

Primary license text: https://www.apache.org/licenses/LICENSE-2.0

## Dependencies and redistributed files

See [THIRD_PARTY.md](THIRD_PARTY.md) for resolved coordinates, license sources and
notice obligations. Maven dependencies are separately resolved, not bundled into
a fat library. Preserve their own notices when redistributing those artifacts.

- `LICENSE`: unmodified Apache-2.0 text.
- `NOTICE`: project origin, upstream tooling/dependencies and key-provisioning scope.
- `licenses/BOUNCY-CASTLE.txt`: upstream permission/copyright text.
- `licenses/JUNIT-EPL-1.0.txt` and `licenses/HAMCREST-BSD-3-Clause.txt`: test-only notices.
- Gradle launcher copyright headers remain intact.

## External public keys and source availability

No original ERCA keys are shipped. The empty default and the existing explicit
key API avoid redistributing the previous key in this project. Developers obtain
their own public material from official sources and assess the applicable terms;
the project does not grant rights to externally obtained keys. See
[KEY_MATERIAL.md](KEY_MATERIAL.md). No general website permission is treated as
blanket clearance, and removing the key does not establish third-party title.

The owner has authorized public availability of this clean source repository.
Its independent history does not import the original application's commits or
the previous key-bearing preparation history. See
[source history and verification](RELEASE_PREPARATION.md).

Public source availability is separate from package distribution. No public
package-publishing endpoint or upload credential is configured in this project.
The supplied Maven tasks publish only to a local development cache; the technical
version remains `0.1.0-SNAPSHOT`.
