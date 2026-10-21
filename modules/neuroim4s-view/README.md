# Volume view

`neuroim4s-view` provides the portable volume-view model, interaction, scene compilation, and typed layer contracts.

Optional render providers live in `neuroim4s-view-canvas`, `neuroim4s-view-java2d`, and `neuroim4s-view-javafx`.

Run its shared suite on both platforms with:

```sh
sbt -J-Xmx4g viewJVM/test viewJS/test
```
