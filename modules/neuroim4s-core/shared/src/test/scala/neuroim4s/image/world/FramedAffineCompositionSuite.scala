package neuroim4s.image.world

import neuroim4s.spaces.*
import spatial4s.D3
import reframe4s.lie.FramedAffine

class FramedAffineCompositionSuite extends munit.FunSuite:
  test("maps between different world frames cannot be composed out of order"):
    val native = FrameCatalog.frame(WorldSpace.declare("subject").toOption.get)
    val toMni = FramedAffine.identity(native)
    val mniToMni6 = FramedAffine.between(Spaces.MNI152NLin2009cAsym, Spaces.MNI152NLin6Asym)(image4s.geometry.Affine.identity[D3])
    val errors = compileErrors("toMni.andThen(mniToMni6)")
    assert(errors.nonEmpty, "composing native->native with MNI2009c->MNI6 must not compile")
    assertNoDiff(compileErrors("mniToMni6.andThen(FramedAffine.identity(Spaces.MNI152NLin6Asym))"), "")
