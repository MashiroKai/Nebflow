package nebflow.core.task

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

enum TaskStatus:
  case Pending, InProgress, Completed, Failed

object TaskStatus:

  given Codec[TaskStatus] = io.circe.Codec.from(
    io.circe.Decoder.decodeString.emap {
      case "pending" => Right(TaskStatus.Pending)
      case "in_progress" => Right(TaskStatus.InProgress)
      case "completed" => Right(TaskStatus.Completed)
      case "failed" => Right(TaskStatus.Failed)
      case other => Left(s"Unknown task status: $other")
    },
    io.circe.Encoder.encodeString.contramap {
      case TaskStatus.Pending => "pending"
      case TaskStatus.InProgress => "in_progress"
      case TaskStatus.Completed => "completed"
      case TaskStatus.Failed => "failed"
    }
  )

end TaskStatus

case class Task(
  id: String,
  subject: String,
  description: String,
  activeForm: Option[String] = None,
  status: TaskStatus = TaskStatus.Pending,
  blocks: List[String] = Nil,
  blockedBy: List[String] = Nil,
  createdAt: Option[String] = None,
  updatedAt: Option[String] = None
)

object Task:
  given Codec[Task] = deriveCodec

case class TaskCreateInput(
  subject: String,
  description: String,
  activeForm: Option[String] = None
)

object TaskCreateInput:
  given Codec[TaskCreateInput] = deriveCodec

case class TaskUpdateInput(
  subject: Option[String] = None,
  description: Option[String] = None,
  activeForm: Option[String] = None,
  status: Option[TaskStatus] = None,
  addBlocks: Option[List[String]] = None,
  addBlockedBy: Option[List[String]] = None,
  removeBlocks: Option[List[String]] = None,
  removeBlockedBy: Option[List[String]] = None
)

object TaskUpdateInput:
  given Codec[TaskUpdateInput] = deriveCodec
