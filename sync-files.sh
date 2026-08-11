#!/bin/bash

# CNB 文件同步脚本
# 功能：自动检测并同步 WebIDE 工作区所有更改到远程仓库
# 使用方法：在 WebIDE 终端中运行 ./sync-files.sh "提交描述"

# 检查是否在 CNB WebIDE 环境中
if [ ! -d "/workspace" ]; then
    echo "错误：未检测到 CNB WebIDE 环境"
        exit 1
        fi

        # 检查提交描述参数
        if [ $# -eq 0 ]; then
            echo "请提供提交描述，例如："
                echo "  ./sync-files.sh \"添加用户手册文档\""
                    exit 1
                    fi

                    COMMIT_MSG="$1"

                    # 进入工作区
                    cd /workspace

                    # 检查 Git 仓库状态
                    echo "正在检查文件变更..."
                    CHANGES=$(git status --porcelain)

                    if [ -z "$CHANGES" ]; then
                        echo "没有检测到文件变更，无需同步"
                            exit 0
                            fi

                            # 显示变更摘要
                            echo "检测到以下变更："
                            git status -s

                            # 添加所有变更
                            echo "添加所有变更到暂存区..."
                            git add .

                            # 提交变更
                            echo "提交变更..."
                            git commit -m "$COMMIT_MSG"

                            # 获取当前分支
                            CURRENT_BRANCH=$(git symbolic-ref --short HEAD)

                            # 推送到远程仓库
                            echo "推送到远程仓库 (分支: $CURRENT_BRANCH)..."
                            git push origin $CURRENT_BRANCH

                            # 检查推送结果
                            if [ $? -eq 0 ]; then
                                echo "✅ 文件同步成功！"
                                    echo "变更已推送到 CNB 仓库"
                                    else
                                        echo "❌ 推送失败，请检查网络连接和仓库权限"
                                            exit 1
                                            fi
                                            