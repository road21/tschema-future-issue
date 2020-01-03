name := "tschema-future-issue"

version := "0.1"

scalaVersion := "2.12.7"

addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3")
addCompilerPlugin(
  "org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full
)

libraryDependencies ++= List(
  "ru.tinkoff" %% "typed-schema-swagger" % "0.11.6",
  "ru.tinkoff" %% "typed-schema-finagle-env" % "0.11.6",
  "ru.tinkoff" %% "typed-schema-finagle-circe" % "0.11.6",
  "ru.tinkoff" %% "tofu-core" % "0.6.0",
  "org.manatki" %% "derevo-cats-tagless" % "0.10.5"
)
