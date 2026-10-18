# neuroim4s

Neuroimaging policy over the core Scala 3 imaging providers, cross-compiled to
the JVM and Scala.js.

| Module | Package | Contents | Depends on |
|---|---|---|---|
| `neuroim4s-spaces` | `neuroim4s.spaces` | Anatomical world identities (`WorldSpace`: template, subject scanner-RAS, subject tkRAS, declared, unresolved), their persistent RAS-millimetre frames (`FrameCatalog`, `Spaces`), and NIfTI/GIFTI xform-code space resolution (`SpaceResolver`) | spatial4s |

`neuroim4s-spaces` is deliberately small so volume, surface and atlas libraries
can share one anatomical identity without depending on each other. It has no
image, volume, IO, surface, atlas or MVPA dependency. Generic coordinates and
frame alignment belong to [spatial4s](https://github.com/canardlapin/spatial4s);
maps between worlds, including `WorldLink`, belong to
[reframe4s](https://github.com/canardlapin/reframe4s).

A template world, a sampled resolution, an acquisition and an atlas release are
distinct identities. Only worlds live here: templates, subject scanner-RAS and
tkRAS, and declared or unresolved spaces. Sampled domains, acquisitions and
atlas releases carry their own identities in their owners.

Persisted world ids keep the `scalafim-world:` spelling they had in ScalaFIM,
byte for byte, so existing records and digests that embed them stay valid.

## Build

Scala 3.7.4, sbt 1.11.7, Node.js for the Scala.js tests.

```sh
sbt neuroim4sTestAll
```

Providers are pinned to immutable revisions in `build.sbt`. For coordinated
development, point a provider at a sibling checkout with
`-Dneuroim4s.spatial4s.build=/absolute/path/to/spatial4s`.

## License

Apache-2.0. See [LICENSE](LICENSE).
