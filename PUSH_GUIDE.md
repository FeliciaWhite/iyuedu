# 推送指南 - mmm 分支

> 本文档记录当前项目推送到远程仓库 `mmm` 分支的方法，基于实际操作整理。

---

## 一、仓库信息

| 项目 | 值 |
|------|-----|
| 本地分支 | `mmm` |
| 远程仓库 | `origin` |
| 远程地址 | `https://git.cnb.cool/maotoutou/maot.git` |
| 目标分支 | `origin/mmm` |

---

## 二、基础推送步骤

```bash
# 1. 进入项目目录
cd /workspace

# 2. 查看当前修改
git status

# 3. 添加所有变更
git add .

# 4. 提交（填写有意义的说明）
git commit -m "feat: 你的修改说明"

# 5. 推送到远程 mmm 分支
git push origin mmm
```

---

## 三、认证方式

当前环境已配置好远程地址，使用 **OAuth2 Token 嵌入 URL** 的方式认证：

```bash
# 查看当前远程地址
git remote -v

# 如需重新设置（Token 需从 CNB 平台获取）
git remote set-url origin https://oauth2:<你的Token>@git.cnb.cool/maotoutou/maot.git
```

**注意**：Token 已写入当前 `.git/config`，一般情况下无需重复设置。

---

## 四、强制推送（慎用）

当本地历史与远程不一致，且确定要用本地版本覆盖远程时：

```bash
git push origin mmm --force
```

> 警告：强制推送会覆盖远程提交历史，团队协同时务必谨慎。

---

## 五、常见问题

### 1. `fatal: Authentication failed`
- **原因**：Token 过期或无效。
- **解决**：前往 [CNB 平台](https://cnb.cool) 重新生成个人访问令牌，然后更新 remote URL。

### 2. `rejected: non-fast-forward`
- **原因**：远程有新提交，本地未同步。
- **解决**：先执行 `git pull origin mmm` 合并远程变更，再推送；或确认无误后强制推送。

### 3. 大文件推送失败
- **原因**：APK、zip 等文件过大，超出平台限制。
- **解决**：在 `.gitignore` 中忽略大文件，改用 Release 附件或其他方式分发。

---

## 六、快速命令汇总

```bash
# 查看状态
git status

# 添加并提交
git add . && git commit -m "update"

# 推送
git push origin mmm

# 查看提交日志
git log --oneline -5

# 查看分支
git branch -vv
```

---

**适用分支**：`mmm`
**适用仓库**：`maotoutou/maot`
**最后更新**：2026-05-14
