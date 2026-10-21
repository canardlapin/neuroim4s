# neuroim4s

Neuroimaging policy over the core Scala 3 imaging providers, cross-compiled to
the JVM and Scala.js. This is pre-release development software: APIs, semantics,
and package boundaries may change; no stable support or binary-compatibility
promise is implied. The complete core and optional IO extraction are under development;
no artifact release is claimed.

```scala
import image4s.geometry.{Affine, D3}
import neuroim4s.image.NeuroAffineSyntax.*

val voxelSizes = Affine.identity[D3].neuroVoxelSizes // Vector(1.0, 1.0, 1.0)
```


| Module | Package | Contents | Depends on |
|---|---|---|---|
| `neuroim4s-core` | `neuroim4s.image` | Typed volumes and time series, ordered selections, masks, sampling, orientation, reslicing and image statistics | spaces, image4s, locus4s, Ravel, Gale, reframe4s |
| `neuroim4s-io` (JVM) | `neuroim4s.image.io` | NIfTI anatomical admission and bounded volume/series IO | core, image4s NIfTI |
| `neuroim4s-transform` | `neuroim4s.transform` | ITK/ANTs, FSL, AFNI, FreeSurfer and X5 codecs, anatomical interpretation and conversion | core, image4s NIfTI, reframe4s |
| `neuroim4s-routing` | `neuroim4s.spatial` | Anatomical volume admission, registration routes, sampling and scoped execution | core, transform, graph4s algorithms |
| `neuroim4s-routing-io` (JVM) | `neuroim4s.spatial.io` | Lazy, freshness-checked, bounded NIfTI reads | routing, io |
| `neuroim4s-transform-io` (JVM) | `neuroim4s.transform` | File/gzip entry point, ITK HDF5 and X5 containers | transform, jHDF |
| `neuroim4s-view` | `neuroim4s.view` | Portable slice scenes, layered display, cursor, cache and pure interaction | core, Intaglio core |
| `neuroim4s-view-canvas` (JS) | `neuroim4s.view.canvas` | Browser Canvas host and controller | view, Intaglio Canvas |
| `neuroim4s-view-java2d` (JVM) | `neuroim4s.view.java2d` | Java2D drawing and buffered images | view, Intaglio Java2D |
| `neuroim4s-view-javafx` (JVM) | `neuroim4s.view.javafx` | JavaFX Canvas host | view, Intaglio JavaFX, OpenJFX Provided |
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

Volumes and series retain their exact image4s `Sampled` values and Ravel
storage. `NeuroVolume.fromSampled` admits a three-dimensional image without
trailing axes; `NeuroSeries.fromSampled` requires exactly one Time axis.
Selections carry their locus4s owner and explicit order. Canonical ordinal reads
use logical x/y/z order, with time varying fastest, including strided views.
The core has no ScalaFIM, surface, atlas, MVPA, renderer or file-format dependency.
Generic matrices, grids, axes and finite domains stay in their providers.

The optional JVM IO module consumes image4s headers and codecs directly.
`Nifti.readVolumeIn` and `readSeriesIn` bind a decoded image to anatomical world
evidence; `Nifti.spaceIn` admits a provider header using the same affine selection
policy as decoding. Invalid agreement tolerances now return the typed provider
error from evidence-only calls too. Unit, scaling, disagreement and unresolved
world checks remain explicit. Raw header reading and streaming scalar writing
use `image4s.nifti.Nifti` directly.

The portable test corpus includes neuroim2 parity fixtures and an independent
consumer outside the library package. JVM tests retain bounded streaming and
allocation checks. Fixture resource paths and persisted world identifiers retain
their original spellings.

A complete constructor → selection → dense-output example, including exact
provider ownership and Time-axis refusal, is executable in
[`ImageWorkflowSuite`](modules/neuroim4s-core/shared/src/test/scala/external/neuroim/consumer/ImageWorkflowSuite.scala).
It imports only public APIs from outside the library package.

Transforms retain their typed source/target frames. Their pullback always exists;
a dense field has no forward map unless an inverse asset or a qualified numerical
inverse supplies it. ITK LPS, FSL scaled-voxel and FreeSurfer scanner/tkRAS
interpretation remains explicit policy. Numerical maps, composition, inversion
and resampling use reframe4s; there is no copied solver. Raw NIfTI bytes and
uninterpreted metadata belong to image4s `Nifti1Container`/`Nifti1Writer`.
`neuroim4s-transform` has no file, HDF5, surface, atlas, MVPA or ScalaFIM dependency,
including in its tests. The optional JVM container module supplies platform IO.

The public [`TransformCompositionSuite`](modules/neuroim4s-transform/shared/src/test/scala/consumer/registration/TransformCompositionSuite.scala)
shows two toolkit registrations composed once, with literal independent LPS/RAS
reference coordinates and explicit refusal of an unavailable forward direction.
Native-tool fixture bytes, writer goldens and persisted provenance strings keep
their original identities. ScalaFIM owns its downstream ScenarioHarness workflows in its unpublished
provider admission tests.

## Build

Scala 3.7.4, sbt 1.11.7, Node.js for the Scala.js tests.

```sh
sbt -J-Xmx4g neuroim4sTestAll
# Focused portable transform and optional JVM-container gates:
sbt -J-Xmx4g transformJVM/test transformJS/test transformIoJVM/test
```

Providers are pinned to immutable revisions in `build.sbt`. For coordinated
development, point a provider at a sibling checkout with
`-Dneuroim4s.spatial4s.build=/absolute/path/to/spatial4s` or
`-Dneuroim4s.image4s.build=/absolute/path/to/image4s` (and equivalently
`neuroim4s.reframe4s.build` for reframe4s). The image4s pin matches
the ScalaFIM consumer; spatial4s overrides are forwarded into image4s so nominal
coordinate types retain one owner throughout the graph.

## License

Apache-2.0. See [LICENSE](LICENSE).

## Anatomical volume routing

Use `neuroim4s-routing` when a volume workflow needs registration routes and
ordered nearest/trilinear sampling. It uses provider-owned frames and grids,
caller-owned node/registration IDs, and reframe4s maps; it has no surface, atlas,
response or dataset dependency. `VolumeReference` names native and template
volumes. `VolumeGrid.from` checks D3 admission and mask congruence once.

`VolumeGraph.path` prefers an available forward route and then, when enabled,
tries geometric inverses with an explicit quality penalty. `VolumeExecution`
compiles ordered target rows into one Gale sparse sampling pass. A
`VolumeSource` supplies only the source support and observations requested by
materialization, in request order. Typed failures retain missing domains/maps,
wrong frame owners, unsampled endpoints and source availability.

`neuroim4s-routing-io` adds `NiftiVolumeSource` on JVM. Preparing it does no IO;
the first validation pins the file revision. Later changes are stale, and every
read closes its channel before returning. Compressed sources require explicit
staging. Portable routing never resolves this platform module.

The executable public examples and independent analytic/binary witnesses live
in `consumer.volume` suites under the routing modules. Run them with:

```sh
sbt -J-Xmx4g routingJVM/test routingJS/test routingIoJVM/test
```

`neuroim4s.graph4s.build` supports an explicit local graph4s checkout override
for coordinated development, just like the existing provider overrides.

## Optional volume views

Use `neuroim4s-view` for orthogonal slice scenes and pure interaction. Colorizers,
display windows, thresholds and drawing commands come directly from Intaglio.
The core, spaces, routing, transform and IO modules do not depend on the view
projects or any renderer. Platform hosts do not own application listeners,
threads, stages or browser lifecycle.

See [the viewer guide](docs/image-viewer.md) and the
[browser workflow](docs/benchmarks/image-view-browser.md). Run bounded gates with:

```sh
sbt -J-Xmx4g viewJVM/test viewJava2dJVM/test viewJavafxJVM/test
sbt -J-Xmx4g viewJS/test viewCanvasJS/test
```

The JavaFX contract tests compile scenes and translate events without starting
the toolkit; concrete Canvas drawing needs a separate native display witness.
`neuroim4s.intaglio.build=/absolute/path/to/intaglio` is the explicit development
override; ordinary builds use the exact Intaglio source revision in `build.sbt`.
