package consumer.volumeview

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import javafx.application.Platform
import javafx.scene.{Group, Scene}
import javafx.scene.canvas.Canvas
import javafx.stage.Stage

import intaglio.{DeviceContext, DisplayWindow, ScalarColorizer}
import neuroim4s.image.*
import neuroim4s.view.*
import neuroim4s.view.javafx.JavaFxViewerHost

/** Opt-in native drawing receipt for the JavaFX Canvas adapter.
  *
  * Baseline input: a constant 3D scalar volume of 1.0, nearest sampling, and
  * display window [0.0, 2.0]. Every interior panel pixel must be opaque gray
  * (128, 128, 128). Crosshairs and orientation labels are disabled so this
  * probe checks the drawing adapter rather than decoration placement.
  */
object NativeCanvasProbe:
  private val DeviceWidth = 500.0
  private val DeviceHeight = 350.0
  private val SampleFractions = Vector(0.25, 0.75)

  def main(args: Array[String]): Unit =
    val done = new CountDownLatch(1)
    val failure = new AtomicReference[Throwable](null)

    // Defer until the toolkit has completed startup. Closing it from the
    // startup callback races Glass initialization on macOS.
    try Platform.startup(() => Platform.runLater(() => renderOnFxThread(done, failure)))
    catch
      case _: IllegalStateException => Platform.runLater(() => renderOnFxThread(done, failure))

    if !done.await(30L, TimeUnit.SECONDS) then
      Platform.exit()
      throw new IllegalStateException("JavaFX Canvas probe timed out before producing a snapshot")

    Option(failure.get).foreach { error =>
      throw new RuntimeException("JavaFX Canvas probe failed", error)
    }

  private def renderOnFxThread(done: CountDownLatch, failure: AtomicReference[Throwable]): Unit =
    var stage: Stage = null
    try
      val (model, session) = baseline()
      val canvas = new Canvas(DeviceWidth, DeviceHeight)
      stage = new Stage()
      stage.setScene(new Scene(new Group(canvas), DeviceWidth, DeviceHeight))
      stage.show()

      val compiled = JavaFxViewerHost
        .renderCanvas(model, session, canvas.getGraphicsContext2D)
        .fold(error => throw new IllegalStateException(error.message), identity)
      val snapshot = canvas.snapshot(null, null)
      val reader = snapshot.getPixelReader
      val samples =
        compiled.frame.panels.all.flatMap { panel =>
          SampleFractions.flatMap { horizontal =>
            SampleFractions.map { vertical =>
              val (x, y) = devicePoint(panel, horizontal, vertical, session.device)
              val argb = reader.getArgb(x, y)
              requireGray(argb, panel.anatomicalPlane, x, y)
            }
          }
        }

      println(s"NativeCanvasProbe passed panels=${compiled.frame.panels.all.size} samples=${samples.size} expectedArgb=0xff808080")
    catch
      case error: Throwable => recordFailure(failure, error)
    finally
      try
        if stage != null then stage.close()
      catch
        case error: Throwable => recordFailure(failure, error)
      finally
        done.countDown()
        Platform.exit()

  private def baseline(): (ViewerModel, ViewerSession) =
    val sampleSpace =
      SampleSpaces
        .requireVolumeD3(SampleSpaces(Vector(3, 3, 3)))
        .fold(error => throw new IllegalArgumentException(error.message), identity)
    val volume = SomeScalarVolume.unsafeCopyFromCanonicalArray(
      Array.fill[Double](sampleSpace.grid.shape.product)(1.0),
      sampleSpace,
      "native-canvas-constant"
    )
    val layer = SliceLayer(
      LayerId.unsafe("constant"),
      volume,
      SliceSampling.Nearest(0.0),
      ScalarColorizer(DisplayWindow.unsafe(0.0, 2.0))
    )
    val model = ViewerModel.unsafe(sampleSpace.grid, Vector(layer))
    val state = ViewerState.centered(sampleSpace.grid).copy(
      showCrosshair = false,
      showOrientationLabels = false
    )
    model -> ViewerSession(state, DeviceContext.unsafe(DeviceWidth, DeviceHeight))

  private def devicePoint(
    panel: PanelReceipt,
    horizontal: Double,
    vertical: Double,
    device: DeviceContext
  ): (Int, Int) =
    val rootX = panel.rect.left + horizontal * panel.rect.width
    val rootY = panel.rect.bottom + vertical * panel.rect.height
    math.floor(rootX * device.width).toInt -> math.floor((1.0 - rootY) * device.height).toInt

  private def requireGray(argb: Int, plane: AnatomicalPlane, x: Int, y: Int): Unit =
    val alpha = (argb >>> 24) & 0xff
    val red = (argb >>> 16) & 0xff
    val green = (argb >>> 8) & 0xff
    val blue = argb & 0xff
    if alpha != 255 || red != 128 || green != 128 || blue != 128 then
      throw new AssertionError(
        f"$plane panel pixel at ($x, $y) was argb=0x$argb%08x; expected opaque RGB(128, 128, 128)"
      )

  private def recordFailure(failure: AtomicReference[Throwable], error: Throwable): Unit =
    if !failure.compareAndSet(null, error) then failure.get.addSuppressed(error)
