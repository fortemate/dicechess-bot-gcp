import sbt.{ given, * }

// Common settings (applied to all subprojects in sbt 2.x)
organization := "lv.id.jc"
version      := "0.1.0-SNAPSHOT"
scalaVersion := "3.8.4"

description := "Dice Chess webhook bot in Scala: the engine's Monte-Carlo search on the JVM, containerised for Google Cloud Run."

// Both the engine and the webhook runtime live in GitHub Packages, which requires authentication
// even for public packages (read:packages scope). GitHub Packages' Maven registry is
// per-repository, so each artifact needs its own resolver entry — but both share one host, so the
// single credentials block below covers both.
resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/fortemate/dicechess-engine"
resolvers += "GitHub Packages (dicechess-bot-runtime)" at
  "https://maven.pkg.github.com/fortemate/dicechess-bot-runtime"

// Credentials for that resolver, evaluated on every load — even for offline tasks — so we keep it
// free of network calls: GitHub Packages validates only the token (the password) and accepts any
// non-empty username. CI exports GITHUB_TOKEN; locally we read it from the gh CLI, which returns
// the token from the OS keychain without touching the network (works offline; never lands in a file).
def ghValue(envVar: String, ghArgs: String*): Option[String] =
  sys.env
    .get(envVar)
    .filter(_.nonEmpty)
    .orElse(scala.util.Try(scala.sys.process.Process("gh" +: ghArgs).!!.trim).toOption)
    .filter(_.nonEmpty)

credentials ++= (for {
  token <- ghValue("GITHUB_TOKEN", "auth", "token")
  user = sys.env.get("GITHUB_ACTOR").filter(_.nonEmpty).getOrElse("git")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val DiceChessEngineVersion     = "0.4.1"
val DiceChessBotRuntimeVersion = "1.0.0"
val MunitVersion               = "1.3.5"

lazy val root = (project in file("."))
  .settings(
    name := "dicechess-bot-gcp",
    Compile / mainClass := Some("dicechess.bot.Main"),
    testFrameworks += new TestFramework("munit.Framework"),
    libraryDependencies ++= Seq(
      // The whole point: the real engine as a dependency — MonteCarloSearch, TimeManager,
      // FenParser, TurnGenerator. Pulls circe transitively (engine's OpeningBookParser).
      "com.fortemate" %% "dicechess-engine"     % DiceChessEngineVersion,
      // Plain `%`, not `%%` — a Java artifact, not cross-built per Scala version: HMAC signing,
      // the ownership handshake, TurnContext, and the JDK HttpServer (CustomHandlerServer).
      "com.fortemate" % "dicechess-bot-runtime" % DiceChessBotRuntimeVersion,
      "io.circe"      %% "circe-parser"         % "0.14.16" % Test,
      "org.scalameta" %% "munit"                % MunitVersion % Test
    ),
    // One runnable fat jar the Cloud Run container executes. A fixed output path (not the
    // cross-version target dir) keeps the Dockerfile's COPY deterministic.
    // In sbt 2.x, assembly settings need to be scoped to the assembly task
    assembly / mainClass := Some("dicechess.bot.Main"),
    assembly / assemblyJarName := "dicechess-bot-gcp.jar",
    // In sbt 2.x, target.value resolves to target/out/jvm/scala-<ver>/<project>/
    // so we need to adjust the path accordingly
    assembly / assemblyOutputPath := target.value / "dicechess-bot-gcp.jar",
    // Pragmatic merge for a single-main fat jar: drop signatures/manifests/module-info, concat
    // service registries, take-first for the rest (no library here needs a smarter policy).
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*)
          if xs.nonEmpty && {
            val n = xs.last.toLowerCase; n.endsWith(".sf") || n.endsWith(".dsa") || n.endsWith(".rsa")
          } =>
        MergeStrategy.discard
      case PathList("META-INF", "MANIFEST.MF")       => MergeStrategy.discard
      case PathList("META-INF", "services", _ @ _*)  => MergeStrategy.concat
      case x if x.endsWith("module-info.class")      => MergeStrategy.discard
      case _                                         => MergeStrategy.first
    }
  )
