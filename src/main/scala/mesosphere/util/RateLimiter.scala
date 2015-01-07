package mesosphere.util

import mesosphere.marathon.state.{ Timestamp, AppDefinition, PathId }

import scala.concurrent.duration._
import scala.util.Try

import org.apache.log4j.Logger

class RateLimiter {

  private val log = Logger.getLogger(getClass.getName)

  protected case class Delay(
    current: FiniteDuration,
    future: Iterator[FiniteDuration])

  protected[this] val maxLaunchDelay = 1.hour

  protected[this] var taskLaunchDelays = Map[(PathId, Timestamp), Delay]()

  def getDelay(app: AppDefinition): Deadline =
    taskLaunchDelays.get(app.id -> app.version).map(_.current.fromNow) getOrElse Deadline.now

  def addDelay(app: AppDefinition): Unit = {
    val newDelay = taskLaunchDelays.get(app.id -> app.version) match {
      case Some(Delay(current, future)) => Delay(future.next(), future)
      case None => Delay(
        app.backoff,
        durations(app.backoff, app.backoffFactor)
      )
    }

    log.info(s"Task launch delay for [${app.id}] is now [${newDelay.current.toSeconds}] seconds")

    taskLaunchDelays += ((app.id, app.version) -> newDelay)
  }

  def resetDelay(app: AppDefinition): Unit = {
    if (taskLaunchDelays contains (app.id -> app.version))
      log.info(s"Task launch delay for [${app.id} - ${app.version}}] reset to zero")
    taskLaunchDelays = taskLaunchDelays - (app.id -> app.version)
  }

  /**
    * Returns an infinite lazy stream of exponentially increasing durations.
    *
    * @param initial  the length of the first duration in the resulting stream
    * @param factor   the multiplier used to compute each successive
    *                 element in the resulting stream
    * @param limit    the maximum length of any duration in the stream
    */
  protected[util] def durations(
    initial: FiniteDuration,
    factor: Double,
    limit: FiniteDuration = maxLaunchDelay): Iterator[FiniteDuration] =
    Iterator.iterate(initial) { interval =>
      Try {
        val millis: Long = (interval.toMillis * factor).toLong
        millis.milliseconds min limit
      }.getOrElse(limit)
    }

}
