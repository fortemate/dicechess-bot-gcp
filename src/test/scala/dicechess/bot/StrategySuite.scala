package dicechess.bot

import dicechess.engine.domain.FenParser
import dicechess.engine.search.TurnGenerator

/** The engine does the chess; this suite only proves the wiring: a tiny wall-clock budget still
  * yields one of the engine's own legal turn paths (Monte-Carlo always returns the best material
  * candidate as a fallback, so a legal move comes back even if the deadline elapses before any
  * rollout completes), the no-clock path works, and an unusable DFEN degrades to a pass.
  */
class StrategySuite extends munit.FunSuite:

  private val initialNbk = FenParser.InitialPosition + " NBK"

  private def legalPaths(dfen: String): Set[List[String]] =
    TurnGenerator.generateAllLegalTurnPaths(FenParser.parse(dfen).toOption.get).map(_.map(Strategy.toUci)).toSet

  test("returns one of the engine's own legal turn paths under a tight deadline"):
    val moves = new Strategy(overheadBufferMs = 5, defaultThinkMs = 200).chooseMoves(initialNbk, Some(800L), 3000L)
    assert(moves.nonEmpty, "the opening roll NBK must have legal moves")
    assert(legalPaths(initialNbk).contains(moves), s"$moves must be a legal path")

  test("plays a legal move with no clock (unlimited control)"):
    val moves = new Strategy(overheadBufferMs = 5, defaultThinkMs = 200).chooseMoves(initialNbk, None, 0L)
    assert(moves.nonEmpty)
    assert(legalPaths(initialNbk).contains(moves))

  test("an unusable dfen yields no moves (the server auto-passes)"):
    assertEquals(new Strategy(5, 200).chooseMoves("not-a-fen", Some(1000L), 0L), Nil)

  test("plays a legal move with non-positive clock (Some(0L) and Some(-5L))"):
    val strategy = new Strategy(overheadBufferMs = 5, defaultThinkMs = 200)
    for clock <- Seq(Some(0L), Some(-5L)) do
      val moves = strategy.chooseMoves(initialNbk, clock, 0L)
      assert(moves.nonEmpty, s"expected legal moves for clock $clock")
      assert(legalPaths(initialNbk).contains(moves), s"$moves must be a legal path")

  // Behaviour pin: a 6-field FEN with no dice pool (FenParser.InitialPosition) fails parsing in
  // FenParser (requires 7 fields), resulting in Left(...) and chooseMoves returning Nil.
  test("a 6-field FEN without dice pool yields Nil (behaviour pin)"):
    val moves = new Strategy(overheadBufferMs = 5, defaultThinkMs = 200)
      .chooseMoves(FenParser.InitialPosition, Some(1000L), 0L)
    assertEquals(moves, Nil)
