package nebflow

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

/**
 * Windows 强力递归删除（git-fixture 簇 2026-10-25）。
 *
 * WHY：`git init/add` 写出的 `.git/objects/` 全树落 444（DOS 只读位），Windows 上
 * `os.remove.all` 直接删只读文件抛 `AccessDeniedException`——上一轮 run 的只读
 * 对象残骸会让下一轮 run 在**套件构造期**就炸（initializationError，零事件）。
 * 本 helper 先递归清 DOS 只读位再删；非 Windows 无 DOS 视图，行为同 `os.remove.all`。
 *
 * 清位尽力而为（IoException 吞掉）；删除阶段的真实失败仍如实抛出（fail-loud 不变）。
 */
object ForceRemove:

  def removeAll(p: os.Path): Unit =
    clearReadOnlyBits(p)
    os.remove.all(p)

  private def clearReadOnlyBits(p: os.Path): Unit =
    if Files.exists(p.toNIO) then
      try
        Files
          .walk(p.toNIO)
          .iterator()
          .asScala
          .foreach { x =>
            val dos = Files.getFileAttributeView(x, classOf[java.nio.file.attribute.DosFileAttributeView])
            if dos != null then
              val attrs = dos.readAttributes()
              if attrs.isReadOnly then dos.setReadOnly(false)
          }
      catch case _: Throwable => ()
end ForceRemove
