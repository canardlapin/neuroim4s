package consumer.registration

import image4s.geometry.{D3, Frame, Point}
import neuroim4s.transform.{Frames, PushAvailability, TransformError, TransformProvenance, TransformSource, WorldTransform}
import neuroim4s.transform.itk.{ItkLinearInterpretation, ItkTextCodec}

/** Public composition from a package outside neuroim4s; reference coordinates are literal LPS/RAS arithmetic. */
class TransformCompositionSuite extends munit.FunSuite:
  private def right[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private val moving = right(Frame.named[D3]("external moving"))
  private val anatomical = right(Frame.named[D3]("external anatomical"))
  private val template = right(Frame.named[D3]("external template"))

  private def itk(dx: Int, dy: Int, dz: Int): TransformSource = TransformSource.Text(
    s"#Insight Transform File V1.0\n# Transform 0\nTransform: AffineTransform_double_3_3\nParameters: 1 0 0 0 1 0 0 0 1 $dx $dy $dz\nFixedParameters: 0 0 0\n"
  )

  test("two public toolkit registrations compose once, retain owners and distinguish pull from push"):
    val first = right(ItkLinearInterpretation.linear(right(ItkTextCodec.decode(itk(1, 2, 3))), Frames[moving.type, anatomical.type](moving, anatomical),
      neuroim4s.transform.AssetRef("first", None), neuroim4s.transform.TransformFormat.ItkText))
    val second = right(ItkLinearInterpretation.linear(right(ItkTextCodec.decode(itk(4, -5, 6))), Frames[anatomical.type, template.type](anatomical, template),
      neuroim4s.transform.AssetRef("second", None), neuroim4s.transform.TransformFormat.ItkText))
    val chain: WorldTransform.Linear[moving.type, template.type] = right(first.andThen(second))
    assert(chain.source eq moving)
    assert(chain.target eq template)
    val target = right(Point.in(template)(10.0, 20.0, 30.0))
    val source = right(chain.pullPoint(target))
    source.coordinates.zip(Vector(5.0, 23.0, 39.0)).foreach((a, e) => assertEqualsDouble(a, e, 0.0))
    right(chain.mapPoint(source)).coordinates.zip(target.coordinates).foreach((a, e) => assertEqualsDouble(a, e, 0.0))
    assertEquals(chain.provenance.steps.count {
      case TransformProvenance.Step.Read(_, _) => true
      case _ => false
    }, 2)

  test("a pullback-only public transform never invents its forward direction"):
    val affine = right(ItkLinearInterpretation.linear(right(ItkTextCodec.decode(itk(1, 2, 3))), Frames[moving.type, anatomical.type](moving, anatomical),
      neuroim4s.transform.AssetRef("one-way", None), neuroim4s.transform.TransformFormat.ItkText))
    val oneWay = WorldTransform.Mapped(affine.pull, PushAvailability.Unavailable[moving.type, anatomical.type](), affine.provenance)
    val source = right(Point.in(moving)(1.0, 2.0, 3.0))
    oneWay.mapPoint(source) match
      case Left(TransformError.NoForwardMap(_)) => ()
      case other => fail(s"unexpected forward map: $other")
    assert(oneWay.invert.isLeft)
    assert(oneWay.push.isEmpty)
