const container = document.getElementById('logos');
const status = document.getElementById('status');
try {
  const [catalogResponse, currentResponse] = await Promise.all([fetch('/brand/logos.json'), fetch('/brand/current.json')]);
  if (!catalogResponse.ok || !currentResponse.ok) throw new Error('资源库加载失败');
  const catalog = await catalogResponse.json();
  const current = await currentResponse.json();
  if (status) status.textContent = `统一配置当前使用：${catalog[current.active]?.name || current.active}。切换由项目统一同步到官网、客户端、登录页和邮件，不再保存浏览器单独偏好。`;
  for (const [id, item] of Object.entries(catalog)) {
    const card = document.createElement('article');
    const previews = document.createElement('div');
    previews.className = 'previews';
    for (const variant of ['light', 'dark']) {
      const img = document.createElement('img'); img.src = item[variant];
      img.alt = item.name; img.className = variant; previews.append(img);
    }
    const title = document.createElement('h2'); title.textContent = item.name;
    const command = document.createElement('code');
    command.textContent = `python3 scripts/switch-logo.py ${id} --website <官网目录> --auth-repo <登录服务目录>`;
    card.append(previews,title,command); container?.append(card);
  }
} catch (error) { if (status) status.textContent = error instanceof Error ? error.message : '资源库加载失败'; }
