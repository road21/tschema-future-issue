package tshemaIssue

import cats.{Eval, Monad, ~>}
import cats.effect.ExitCode
import com.twitter.finagle
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.{Await, Promise}
import monix.eval.{Task, TaskApp}
import monix.execution.Scheduler
import org.manatki.derevo.derive
import org.manatki.derevo.tagless.functorK
import ru.tinkoff.tschema.finagle.{LiftHttp, MkService, RoutedPlus, RunHttp}
import ru.tinkoff.tschema.swagger.{MkSwagger, SwaggerBuilder}
import ru.tinkoff.tschema.syntax._
import ru.tinkoff.tschema.finagle.routing.EnvRouting
import ru.tinkoff.tschema.utils.{SubString, functionK}
import tofu.env.Env
import ru.tinkoff.tschema.finagle.circeInstances._
import cats.tagless.syntax.functorK._
import tofu.Execute

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

@derive(functorK)
trait SomeService[F[_]] {
  def foo: F[Int]
  def foo2(arg: Option[Int]): F[Int]
}

object SomeService {
  implicit val ec = ExecutionContext.global
  val someService: SomeService[Future] = new SomeService[Future] {
    override def foo: Future[Int] = Future {
      println("running foo")
      Random.nextInt()
    }

    override def foo2(arg: Option[Int]): Future[Int] = Future {
      println("running foo2")
      Random.nextInt()
    }
  }

  def create[F[_]](implicit F: Execute[F]): SomeService[F] =
    someService.mapK(functionK.apply(x => F.deferFuture(x)))
}

class SomeModule[F[_]: Monad: RoutedPlus, G[_]: LiftHttp[F, *[_]]: Monad](
  S: SomeService[G]
) {
  object DummyApi {
    def api =
      prefix('someModule) |> (foo <|> foo2)

    def foo = keyPrefix('foo) |> get |> $$[Int]
    def foo2 =
      keyPrefix('foo2) |> delete |> queryParam[Option[Int]]('arg) |> $$[Int]
  }

  def route: F[Response] = MkService[F](DummyApi.api)(S)
  def swagger: SwaggerBuilder = MkSwagger(DummyApi.api)
}

object Application extends TaskApp {
  type HttpTask[A] = EnvRouting.EnvHttp[Unit, A]

  implicit val liftHttp: LiftHttp[HttpTask, Task] =
    new LiftHttp[HttpTask, Task] {
      override def apply[A](fa: Task[A]): HttpTask[A] = Env.fromTask(fa)
    }

  implicit val httpScheduler: Scheduler = Scheduler.forkJoin(
    Runtime.getRuntime.availableProcessors() * 2,
    Runtime.getRuntime.availableProcessors() * 4,
    "main-scheduler"
  )

  implicit val runnable: RunHttp[HttpTask, Task] =
    (fresp: HttpTask[Response]) =>
      Task.now((request: Request) => {
        val ctx = EnvRouting[Unit](request, SubString(request.path), 0, ())
        fresp.run(ctx).executeOn(httpScheduler).runToFuture.asTwitter
      })

  override def run(args: List[String]): Task[ExitCode] = {
    implicit val service: SomeService[Task] = SomeService.create

    val module = new SomeModule[HttpTask, Task](service)

    for {
      srv <- RunHttp.run[Task](module.route)
      res <- Task.delay(finagle.Http.serve(s"localhost:8082", srv))
      _ <- Task.delay(Await.ready(res))
    } yield ExitCode.Success
  }

  implicit class ScalaFutureExt[A](val sf: scala.concurrent.Future[A])
      extends AnyVal {
    def asTwitter(implicit e: ExecutionContext): com.twitter.util.Future[A] = {
      val promise: Promise[A] = new Promise[A]()
      sf.onComplete {
        case Success(value)     => promise.setValue(value)
        case Failure(exception) => promise.setException(exception)
      }
      promise
    }
  }
}
