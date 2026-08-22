// Produces the single runnable fat jar the Cloud Run container runs (`sbt assembly`).
// Note: For sbt 2.x, sbt automatically adds _sbt2_3 suffix, so use the base plugin name
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.4.1")
