package dcos.metronome.scheduler.impl

import akka.event.EventStream
import dcos.metronome.eventbus.TaskStateChangedEvent
import dcos.metronome.scheduler.TaskState
import dcos.metronome.utils.time.Clock
import mesosphere.marathon.core.task.bus.MarathonTaskStatus.WithMesosStatus
import mesosphere.marathon.core.task.bus.TaskChangeObservables.TaskChanged
import mesosphere.marathon.core.task.update.TaskUpdateStep
import mesosphere.marathon.core.task.{ TaskStateChange, TaskStateOp }

import scala.concurrent.Future

class NotifyOfTaskStateOperationStep(eventBus: EventStream, clock: Clock) extends TaskUpdateStep {
  override def name: String = "NotifyOfTaskStateOperationStep"

  override def processUpdate(taskChanged: TaskChanged): Future[_] = {
    taskState(taskChanged).foreach { state =>
      val event = TaskStateChangedEvent(
        taskId = taskChanged.taskId,
        taskState = state,
        timestamp = clock.now()
      )
      eventBus.publish(event)
    }

    Future.successful(())
  }

  // FIXME: we shouldn't need a translation from TaskChanged to a TaskState - Marathon should provide clear information
  // about each tasks state without the need to map over options or match case classes (mesosStatus, launched or Status)
  // for now, this is a super ugly translation:
  private[this] def taskState(taskChanged: TaskChanged): Option[TaskState] = {
    (taskChanged.stateOp, taskChanged.stateChange) match {

      // FIXME: what if a task has been killed by the overdueTasksActor?

      // A Mesos status update disregarding the effect
      case (TaskStateOp.MesosUpdate(task, WithMesosStatus(mesosStatus), _), _) => Some(TaskState(mesosStatus))

      // a new launched task
      case (TaskStateOp.LaunchEphemeral(_), TaskStateChange.Update(_, _)) => Some(TaskState.Created)

      // a new launched task
      case (_: TaskStateOp.LaunchOnReservation, TaskStateChange.Update(_, _)) => Some(TaskState.Created)

      // Whatever lead to a TaskStateChange.Update if we have a mesosStatus
      case (_, TaskStateChange.Update(task, _)) if task.mesosStatus.isDefined => Some(TaskState(task.mesosStatus.get))

      // Whatever lead to a TaskStateChange.Update if we have no mesosStatus
      case (_, TaskStateChange.Update(task, _)) => Some(TaskState.Created)

      // Whatever
      case _ => None
    }
  }

}
