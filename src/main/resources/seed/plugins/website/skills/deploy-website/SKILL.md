---
name: deploy-website
description: Deploy the Stand website (mystand.space) to Vercel production. Use after modifying website files.
---

# /deploy-website — Stand Website Deployment

Deploy changes to mystand.space. The website is a Next.js app hosted on Vercel; pushing to `origin/main` triggers the Vercel production deploy automatically（~45s Ready，Git 集成自动部署）.

## Project Location

```
/Users/kaiyu/.nebflow/projects/nebflow-website
```

GitHub repo: `MashiroKai/nebflow-website`

## Step 1: Commit and Push

```bash
cd /Users/kaiyu/.nebflow/projects/nebflow-website
git add -A
git commit -m "<descriptive message>"
git push origin main
```

## Step 2: Deploy（push 即自动部署）

push origin/main ⇒ Vercel 自动生产部署（~45s Ready）：push 后 Vercel Git 集成自动 build 并部署生产，alias 到 `mystand.space`。用 `vercel --prod` 或部署 API 观察进度即可。

`vercel --prod` 仅作为 **Git 集成失效时的手动兜底**，正常流程不需要：

```bash
vercel --prod
```

## Step 3: Verify

发布验收用**部署 API 或内容指纹**：部署 API 看 dpl `state=READY`，或直接抓线上内容指纹——

```bash
curl -s https://mystand.space | grep -o "install-beta\|install\.sh" | head -3
```

🔴 禁用 `?cb=` 查询串绕缓存：静态预渲染路由不参与缓存键，只会读到陈旧边缘缓存，造成『没部署』误判（读数不对 ≠ 没部署）。

## Key Facts

- **Framework**: Next.js 16+ with Turbopack, App Router
- **Vercel project**: `nebflow-website` (org: `kais-projects-e8bd6cfb`)
- **Build command**: `npm run build` (runs `next build`)
- **Domains**: `mystand.space` (production alias; legacy `nebflow.space` retired)
- **Auto-deploy on push**: push origin/main ⇒ Vercel 自动生产部署（~45s Ready）；发布后用部署 API 或内容指纹验收，禁用 `?cb=` 查询串绕缓存（静态预渲染路由不参与缓存键，会读到陈旧边缘缓存造成『没部署』误判）。
- **Install scripts** live in `public/`: `install.sh`, `install.ps1`, `install-beta.sh`, `install-beta.ps1`
- **Bilingual**: `/en` and `/zh` routes, dictionaries in `lib/dictionaries.ts`

> 依据：2026-09-15 双批实测——push 后 `dpl_3ASZwaPHG8EcsKyz8f48mrzC75u5` createdAt==push+2s、Ready 45s、alias nebflow.space（历史记录，时值旧域）；缓存键缺陷曾致 16min 陈旧读数（`?cb=` 不可作为部署判据）。
