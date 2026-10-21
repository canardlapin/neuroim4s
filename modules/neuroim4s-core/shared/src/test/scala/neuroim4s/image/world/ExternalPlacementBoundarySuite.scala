package example

import scala.compiletime.testing.typeCheckErrors

class ExternalPlacementBoundarySuite extends munit.FunSuite:
  test("external code cannot fabricate ownership evidence or invoke the provider capture boundary"):
    val gridWitness = typeCheckErrors("""
      import neuroim4s.spaces.*
      import neuroim4s.image.GridSpec
      summon[FrameOwned[GridSpec]]
    """)
    assert(gridWitness.nonEmpty)
    val arbitraryWitness = typeCheckErrors("""
      import neuroim4s.spaces.*
      import spatial4s.*
      new FrameOwned[Rebind.PointIn]:
        def frameOf[F <: Frame[D3]](value: Point[F, D3]): F = value.frame
    """)
    assert(arbitraryWitness.nonEmpty)
    val capture = typeCheckErrors("""
      import neuroim4s.spaces.*
      import spatial4s.*
      val frame = Spaces.MNI152NLin2009cAsym
      val point = Point.in(frame)(1.0, 2.0, 3.0).toOption.get
      Placed.capture[frame.type, Rebind.PointIn](frame)(point, frame)
    """)
    assert(capture.exists(_.message.contains("cannot be accessed")), capture.map(_.message))
