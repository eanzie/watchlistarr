ThisBuild / scalaVersion := "2.13.18"

lazy val root = (project in file("."))
  .settings(
    name                 := "watchlistarr",
    version              := "0.2.7",
    assembly / mainClass := Some("Server"),
    Compile / resourceGenerators += Def.task {
      val file = (Compile / resourceManaged).value / "version.properties"
      val content = s"version=${version.value}\n"
      IO.write(file, content)
      Seq(file)
    }.taskValue
  )

// Versions below are kept in step with what http4s 0.23.x is built against, so that
// sbt does not have to evict anything: cats-effect, fs2, cats-core, vault and
// case-insensitive all match the versions declared by http4s-core 0.23.36.
val caseInsensitiveVersion    = "1.5.0"
val catsCoreVersion           = "2.13.0"
val catsEffectVersion         = "3.7.0"
val circeGenericExtrasVersion = "0.14.4"
val circeVersion              = "0.14.16"
val fs2Version                = "3.13.0"
val http4sVersion             = "0.23.36"
val logbackVersion            = "1.5.38"
val scaffeineVersion          = "5.3.0"
val scalamockVersion          = "7.5.5"
val scalatestVersion          = "3.2.20"
val shapelessVersion          = "2.3.13"
val slf4jVersion              = "2.0.18"
val snakeYamlVersion          = "2.6"
val vaultVersion              = "3.7.0"

libraryDependencies ++= Seq(
  "org.scala-lang"      % "scala-library"        % scalaVersion.value % "provided",
  "ch.qos.logback"      % "logback-classic"      % logbackVersion     % Runtime,
  "org.slf4j"           % "slf4j-api"            % slf4jVersion,
  "org.http4s"         %% "http4s-ember-client"  % http4sVersion,
  "org.http4s"         %% "http4s-circe"         % http4sVersion,
  "org.http4s"         %% "http4s-client"        % http4sVersion,
  "org.http4s"         %% "http4s-core"          % http4sVersion,
  "co.fs2"             %% "fs2-core"             % fs2Version,
  "co.fs2"             %% "fs2-io"               % fs2Version,
  "com.chuusai"        %% "shapeless"            % shapelessVersion,
  "io.circe"           %% "circe-core"           % circeVersion,
  "org.typelevel"      %% "case-insensitive"     % caseInsensitiveVersion,
  "org.typelevel"      %% "cats-core"            % catsCoreVersion,
  "org.typelevel"      %% "cats-effect"          % catsEffectVersion,
  "org.typelevel"      %% "cats-effect-kernel"   % catsEffectVersion,
  "org.typelevel"      %% "vault"                % vaultVersion,
  "io.circe"           %% "circe-generic"        % circeVersion,
  "io.circe"           %% "circe-generic-extras" % circeGenericExtrasVersion,
  "org.yaml"            % "snakeyaml"            % snakeYamlVersion,
  "com.github.blemale" %% "scaffeine"            % scaffeineVersion   % "compile",
  "io.circe"           %% "circe-parser"         % circeVersion       % Test,
  "org.scalamock"      %% "scalamock"            % scalamockVersion   % Test,
  "org.scalatest"      %% "scalatest"            % scalatestVersion   % Test
)

enablePlugins(JavaAppPackaging)

ThisBuild / assemblyMergeStrategy := {
  case "module-info.class"      => MergeStrategy.discard
  case PathList("META-INF", _*) => MergeStrategy.discard
  case x =>
    val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
    oldStrategy(x)
}
