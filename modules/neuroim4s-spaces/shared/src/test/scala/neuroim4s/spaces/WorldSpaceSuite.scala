package neuroim4s.spaces

import spatial4s.{D3, Frame, FrameRegistry}

class WorldSpaceSuite extends munit.FunSuite:
  private def ok[A](result: Either[SpaceError, A]): A =
    result.fold(error => fail(error.message), identity)

  private val identityAffine = Vector(1.0, 0, 0, -10, 0, 1, 0, -20, 0, 0, 1, -30, 0, 0, 0, 1)
  private val bold = ok(GeometryDigest(Vector(64, 64, 32), identityAffine, 1, 1))
  private val otherGeometry = ok(GeometryDigest(Vector(64, 64, 32), identityAffine.updated(3, -10.5), 1, 1))

  private def native(ns: String, sub: String, entities: Map[String, String], geometry: GeometryDigest = bold) =
    WorldSpace.SubjectNative(ok(DatasetNamespace(ns)), ok(SubjectId(sub)), Some(ok(SessionId("01"))), ok(ReferenceAcquisition(entities, geometry)))

  test("the same subject label in two datasets names two world spaces"):
    val a = native("doi:10.18112/openneuro.ds000001", "sub-01", Map("task" -> "rest"))
    val b = native("doi:10.18112/openneuro.ds000002", "sub-01", Map("task" -> "rest"))
    assertNotEquals(FrameCatalog.frameId(a), FrameCatalog.frameId(b))

  test("two boldrefs in one session are distinct unless task, run and geometry all agree"):
    val run1 = native("ds", "sub-01", Map("task" -> "rest", "run" -> "1"))
    val run2 = native("ds", "sub-01", Map("task" -> "rest", "run" -> "2"))
    val moved = native("ds", "sub-01", Map("task" -> "rest", "run" -> "1"), otherGeometry)
    assertNotEquals(FrameCatalog.frameId(run1), FrameCatalog.frameId(run2))
    assertNotEquals(FrameCatalog.frameId(run1), FrameCatalog.frameId(moved))
    assertEquals(FrameCatalog.frameId(run1), FrameCatalog.frameId(native("ds", "sub-01", Map("run" -> "1", "task" -> "rest"))))

  test("declared spaces are fresh even when their labels match"):
    val first = ok(WorldSpace.declare("my-space"))
    val second = ok(WorldSpace.declare("my-space"))
    assertNotEquals(first, second)
    assertNotEquals(FrameCatalog.frameId(first), FrameCatalog.frameId(second))

  test("a declared space's identity is its token; the label is presentation only"):
    val declared = ok(WorldSpace.declare("my-space"))
    val encoded = WorldSpace.encode(declared)
    assert(!encoded.contains("my-space"), clue = encoded)
    val relabelled = ok(WorldSpace.decode(encoded, declaredLabel = Some("renamed")))
    assertEquals(relabelled, declared)
    assertEquals(relabelled.hashCode, declared.hashCode)
    assertEquals(relabelled.displayName, "renamed")
    assertEquals(FrameCatalog.frameId(relabelled), FrameCatalog.frameId(declared))
    // The frame's metadata carries the label, so a frame restores to the space with its label.
    assertEquals(FrameCatalog.worldOf(FrameCatalog.frame(declared)).map(_.displayName), Right("my-space"))
    // A blank label on restore is normalised to a generic one rather than admitted.
    assertEquals(ok(WorldSpace.decode(encoded, declaredLabel = Some("   "))).displayName, "declared space")

  test("decoding malformed identifiers is a Left, never an exception"):
    val geometry = bold.canonical
    val malformed = Vector(
      s"scalafim-world:native:ds:sub-01::k-%007C$geometry",
      s"scalafim-world:native:ds:sub-01::k%007C$geometry",
      "scalafim-world:native:ds:sub-01::task-rest%007Cnot-a-digest",
      "scalafim-world:native:ds:sub-01::task-rest%007C64x64x32;q1;s1;zz",
      "scalafim-world:native:ds:sub-01::task-rest%007C0x64x32;q1;s1;" + Vector.fill(12)("0").mkString(","),
      "scalafim-world:native:ds:sub-01::task-rest%007C64x64x32;q1;s1;" + Vector.fill(12)("7ff8000000000000").mkString(","),
      "scalafim-world:tkras:ds:bert:%007C64x64x32;q1;s1;1,2",
      "scalafim-world:declared:",
      "scalafim-world:template:",
      "scalafim-world:native:ds",
      "scalafim-world:native:ds:sub-01::task-rest%00"
    )
    malformed.foreach: text =>
      assert(WorldSpace.decode(text).isLeft, clue = text)

  test("a persisted geometry digest must be exactly canonical"):
    val space = native("ds", "sub-01", Map("task" -> "rest"))
    val encoded = WorldSpace.encode(space)
    assertEquals(WorldSpace.decode(encoded), Right(space))
    // Same numbers, non-canonical spelling (a leading '+' on a code) is not the same identifier.
    assert(WorldSpace.decode(encoded.replace("%003Bq1%003B", "%003Bq%002B1%003B")).isLeft)

  test("reference acquisitions reject blank BIDS entities"):
    assert(ReferenceAcquisition(Map("task" -> ""), bold).isLeft)
    assert(ReferenceAcquisition(Map(" " -> "rest"), bold).isLeft)
    assert(ReferenceAcquisition(Map("task" -> "rest"), bold).isRight)

  test("unknown worlds have distinct persistent scopes and reject the legacy shared key"):
    val first = WorldSpace.freshUnresolved()
    val second = WorldSpace.freshUnresolved()
    assertNotEquals(FrameCatalog.frameId(first), FrameCatalog.frameId(second))
    assert(WorldSpace.decode("scalafim-ras-d3").isLeft)
    val restored = ok(WorldSpace.decode(WorldSpace.encode(first)))
    assertEquals(restored, first)
    assert(Frame.align[D3](FrameCatalog.frame(first), FrameCatalog.frame(restored)).isRight)
    assert(SpaceResolver.resolveKnown(SpaceEvidence(assertion = Some(restored))).isLeft)

  test("encoding round-trips every world space, including awkward characters"):
    val spaces = Vector(
      ok(WorldSpace.template("MNI152NLin2009cAsym")),
      native("ns:with:colons%and-dashes_", "sub-01|x", Map("task" -> "a-b_c|d", "acq" -> "é"), otherGeometry),
      WorldSpace.SubjectTkRas(ok(DatasetNamespace("ds")), ok(SubjectId("bert")), ok(ReferenceAcquisition(Map.empty, bold))),
      ok(WorldSpace.declare("label: with % specials")),
      WorldSpace.freshUnresolved()
    )
    spaces.foreach: space =>
      assertEquals(WorldSpace.decode(WorldSpace.encode(space)), Right(space))

  test("independently created frames of one world space align; different spaces do not"):
    val mni = ok(WorldSpace.template("MNI152NLin2009cAsym"))
    val first = FrameCatalog.frame(mni)
    val second = FrameCatalog.frame(mni)
    assert(!first.sameRuntimeOwnerAs(second))
    assert(Frame.align[D3](first, second).isRight)
    assert(Frame.align[D3](first, FrameCatalog.frame(ok(WorldSpace.template("MNI152NLin6Asym")))).isLeft)
    assertEquals(FrameCatalog.worldOf(second), Right(mni))

  test("a persisted world frame restores through the provider registry"):
    val tkras = WorldSpace.SubjectTkRas(ok(DatasetNamespace("ds")), ok(SubjectId("bert")), ok(ReferenceAcquisition(Map.empty, bold)))
    val live = FrameCatalog.frame(tkras)
    val registry = live.record.flatMap(_ => FrameRegistry.empty.register(live)).fold(e => fail(e.message), identity)
    val restored = Frame.restore[D3](FrameCatalog.record(tkras), registry).fold(e => fail(e.message), identity)
    assert(restored.frame.sameRuntimeOwnerAs(live))
    assertEquals(FrameCatalog.worldOf(restored.frame), Right(tkras))

  test("ephemeral and non-RAS frames are not world frames"):
    val ephemeral = Frame.named[D3]("scratch").fold(e => fail(e.message), identity)
    assert(FrameCatalog.worldOf(ephemeral).isLeft)
    val id = FrameCatalog.frameId(ok(WorldSpace.template("MNI152NLin2009cAsym")))
    def keyed(unit: spatial4s.CoordinateUnit, convention: spatial4s.CoordinateConvention) =
      Frame.persistentNamed[D3](id, "MNI152NLin2009cAsym", unit, convention).fold(e => fail(e.message), identity)
    assert(FrameCatalog.worldOf(keyed(spatial4s.CoordinateUnit.Millimeter, spatial4s.CoordinateConvention.RAS)).isRight)
    assert(FrameCatalog.worldOf(keyed(spatial4s.CoordinateUnit.Millimeter, spatial4s.CoordinateConvention.LPS)).isLeft)
    assert(FrameCatalog.worldOf(keyed(spatial4s.CoordinateUnit.Meter, spatial4s.CoordinateConvention.RAS)).isLeft)


  test("persisted world ids and frame records are byte-identical to the ScalaFIM encoding"):
    val digest = "64x64x32;q1;s1;3ff0000000000000,0,0,c024000000000000,0,3ff0000000000000,0,c034000000000000,0,0,3ff0000000000000,c03e000000000000"
    val escapedDigest = digest.replace(";", "%003B").replace(",", "%002C")
    assertEquals(bold.canonical, digest)
    val goldens = Vector(
      ok(WorldSpace.template("MNI152NLin2009cAsym")) -> "scalafim-world:template:MNI152NLin2009cAsym",
      native("ds-01", "01", Map("task" -> "rest", "run" -> "1")) ->
        s"scalafim-world:native:ds-01:01:01:run-1_task-rest%007C$escapedDigest",
      WorldSpace.SubjectNative(ok(DatasetNamespace("ds")), ok(SubjectId("01")), None, ok(ReferenceAcquisition(Map("task" -> "rest"), bold))) ->
        s"scalafim-world:native:ds:01::task-rest%007C$escapedDigest",
      WorldSpace.SubjectTkRas(ok(DatasetNamespace("ds")), ok(SubjectId("bert")), ok(ReferenceAcquisition(Map.empty, bold))) ->
        s"scalafim-world:tkras:ds:bert:%007C$escapedDigest"
    )
    goldens.foreach: (space, id) =>
      assertEquals(WorldSpace.encode(space), id)
      assertEquals(WorldSpace.decode(id), Right(space))
      val key = FrameCatalog.record(space).key
      assertEquals(key.id.value, id)
      assertEquals(key.spatialRank, 3)
      assertEquals(key.unit, spatial4s.CoordinateUnit.Millimeter)
      assertEquals(key.convention, spatial4s.CoordinateConvention.RAS)
      assertEquals(FrameCatalog.record(space).metadata.label, space.displayName)
