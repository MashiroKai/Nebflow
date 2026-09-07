# PoC-d 前端 QA 容器化全链（port=8098, jar=nebflow-assembly-1.4.1-beta.56.jar）

| phase | ms | note |
|---|---|---|
| create | 158 | 容器创建 |
| start | 312 | 容器启动 |
| exec_gateway | 89 | 容器内起隔离实例(detached) |
| health_ready | 2287 | http=200 after 2 polls |
| destroy | 497 | docker rm -f（容器销毁=进程树全灭） |

PASS=7 FAIL=0
