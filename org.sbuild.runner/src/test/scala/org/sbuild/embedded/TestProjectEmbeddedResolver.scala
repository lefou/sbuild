package org.sbuild.embedded

import java.io.File
import java.io.FileWriter
import scala.util.Failure
import org.sbuild.ExportDependencies
import org.sbuild.Project
import org.sbuild.SchemeHandler
import org.sbuild.Target
import org.sbuild.TargetNotFoundException
import org.sbuild.TargetRef.fromString
import org.sbuild.TargetRefs.fromString
import org.sbuild.internal.BuildFileProject
import org.scalatest.FreeSpec
import org.sbuild.SchemeResolver
import org.sbuild.TargetContext
import scala.util.Success

class TestProjectEmbeddedResolver extends FreeSpec {

  def createFile(ctx: String, path: String)(content: String): File = {
    val projectFile = new File(s"target/test/TestProjectEmbeddedResolver/${ctx}/${path}")
    projectFile.getParentFile.mkdirs()
    val writer = new FileWriter(projectFile)
    writer.write(content)
    writer.close()
    projectFile
  }

  "An empty project" - {

    "should not resolve a phony:test target" in {

      val projectFile = createFile("1", "SBuild.scala")("// Dummy File")
      val project: Project = new BuildFileProject(projectFile, projectFile.getParentFile())
      val resolver = new ProjectEmbeddedResolver(project)
      val result = resolver.resolve("phony:test", new NullProgressMonitor())
      assert(result.isFailure)
      assert(result.asInstanceOf[Failure[_]].exception.getClass === classOf[TargetNotFoundException])
    }

    "should not resolve a test target" - {

      "which does not exists as file" in {
        val projectFile = createFile("2", "SBuild.scala")("// Dummy File")
        // ensure, the target file does not exists
        new File(projectFile.getParentFile, "test").delete()

        val project: Project = new BuildFileProject(projectFile, projectFile.getParentFile())
        val resolver = new ProjectEmbeddedResolver(project)
        val result = resolver.resolve("test", new NullProgressMonitor())
        assert(result.isFailure)
        assert(result.asInstanceOf[Failure[_]].exception.getClass === classOf[TargetNotFoundException])
      }

      "but should not fail when the target file exists" in {
        val projectFile = createFile("3", "SBuild.scala")("// Dummy File")
        // create the file with same name as the target
        createFile("3", "test")("//Dummy test file")

        val project: Project = new BuildFileProject(projectFile, projectFile.getParentFile())
        val resolver = new ProjectEmbeddedResolver(project)
        val result = resolver.resolve("test", new NullProgressMonitor())
        assert(result.isSuccess)
        assert(result.get === Seq(new File(projectFile.getParentFile.getAbsoluteFile, "test")))
      }

    }
  }

  "A project with one phony target" - {
    "should resolve that same target" in pending
  }

  "A project with one file target" - {
    "should resolve one file if it exists" in pending
  }

  "ExportDependencies" - {
    val testDep1 = "mvn:de.tototec:de.tototec.cmdoption:0.3.2"
    val testDep2 = "/tmp/dep2.jar"

    "a simple exported dependencies should match" in {
      val projectFile = createFile("export-1", "SBuild.scala")("// Dummy File")
      val project: Project = new BuildFileProject(projectFile, projectFile.getParentFile())

      {
        import org.sbuild.TargetRefs._
        implicit val _baseProject = project

        ExportDependencies("eclipse.classpath", testDep1 ~ testDep2)
      }

      val resolver = new ProjectEmbeddedResolver(project)
      val exportedFiles = resolver.exportedDependencies("eclipse.classpath")
      val expectedFiles = Seq(testDep1, testDep2)
      assert(exportedFiles === expectedFiles)
    }

    "a simple target should export dependencies" in {
      val projectFile = createFile("export-2", "SBuild.scala")("// Dummy File")
      val project: Project = new BuildFileProject(projectFile, projectFile.getParentFile())

      {
        import org.sbuild.TargetRefs._
        implicit val _baseProject = project

        SchemeHandler("mvn", new SchemeResolver() {
          override def localPath(ctx: SchemeHandler.SchemeContext): String = "file:" + ctx.path
          override def resolve(ctx: SchemeHandler.SchemeContext, ctx2: TargetContext): Unit = {}
        })
        Target("phony:deps") dependsOn testDep1 ~ testDep2
      }

      val resolver = new ProjectEmbeddedResolver(project)
      val exportedFiles = resolver.exportedDependenciesFromTarget("deps")
      val expectedFiles = TargetTree(Success("deps"), childs = Seq(TargetTree(Success(testDep1)), TargetTree(Success(testDep2))))
      assert(exportedFiles === expectedFiles)
    }

    "a target with targets as dependencies should export dependencies" in {
      val projectFile = createFile("export-2", "SBuild.scala")("// Dummy File")
      val project: Project = new BuildFileProject(projectFile, projectFile.getParentFile())

      {
        import org.sbuild.TargetRefs._
        implicit val _baseProject = project

        SchemeHandler("mvn", new SchemeResolver() {
          override def localPath(ctx: SchemeHandler.SchemeContext): String = "file:" + ctx.path
          override def resolve(ctx: SchemeHandler.SchemeContext, ctx2: TargetContext): Unit = {}
        })
        Target("phony:deps") dependsOn testDep1
        Target("phony:testDeps") dependsOn "deps" ~ testDep2
      }

      val resolver = new ProjectEmbeddedResolver(project)
      val exportedFiles = resolver.exportedDependenciesFromTarget("testDeps")
      val expectedFiles = TargetTree(Success("testDeps"), childs = Seq(TargetTree(Success("deps"), childs = Seq(TargetTree(Success(testDep1)))), TargetTree(Success(testDep2))))
      assert(exportedFiles === expectedFiles)
    }
  }

}