# neuroim4s

Neuroimaging policy over the core Scala 3 imaging providers, cross-compiled to
the JVM and Scala.js. This is pre-release development software: APIs, semantics,
and package boundaries may change; no stable support or binary-compatibility
promise is implied. The affine-policy core is available from source;
no artifact release is claimed for this addition.

```scala
import image4s.geometry.{Affine, D3}
import neuroim4s.image.NeuroAffineSyntax.*

val voxelSizes = Affine.identity[D3].neuroVoxelSizes // Vector(1.0, 1.0, 1.0)
```


| Module | Package | Contents | Depends on |
|---|---|---|---|
| `neuroim4s-core` | `neuroim4s.image` | Neuroimaging affine policy: voxel spacing, obliquity and voxel-centre-preserving rescaling | image4s geometry |
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

`neuroim4s-core` consumes the existing image4s affine directly. Its rescaling
policy preserves the lower central voxel on even shapes, matching the existing
neuroim2 convention; it does not substitute the geometric midpoint. The moved
implementation is unchanged apart from its package name. Portable consumer tests
cover rotated anisotropic grids, reflections, even/odd shapes and invalid inputs.

The core addition is intentionally bounded. Volume/series admission, parity APIs,
and optional IO remain in ScalaFIM pending subsequent extraction slices. This core
has no surface, atlas, MVPA, ScalaFIM, display or file-format dependency. Generic
matrices, inversion and affine composition remain provider responsibilities.

## Build

Scala 3.7.4, sbt 1.11.7, Node.js for the Scala.js tests.

```sh
sbt neuroim4sTestAll
```

Providers are pinned to immutable revisions in `build.sbt`. For coordinated
development, point a provider at a sibling checkout with
`-Dneuroim4s.spatial4s.build=/absolute/path/to/spatial4s` or
`-Dneuroim4s.image4s.build=/absolute/path/to/image4s`. The image4s pin matches
the ScalaFIM consumer; spatial4s overrides are forwarded into image4s so nominal
coordinate types retain one owner throughout the graph.

## License

Apache-2.0. See [LICENSE](LICENSE).
