# 官方 APK + 独立运行时模块

NinebotEnhance 是独立的 NPA/Xposed 运行时模块。它通过模块框架在 `cn.ninebot.ninebot` 进程中加载，不修改九号 APK 的文件内容。

## 正确安装结构

```text
官方九号 APK（保持原包、原包名、原证书）
        +
NinebotEnhance 独立模块 APK
        +
NPA/LSPosed/兼容运行时框架
```

模块作用域为 `cn.ninebot.ninebot`，入口为 `dev.ichinomiya.ninebotenhance.hook.MirrorModule`。

## 不要使用

不要安装或继续生成以下类型的九号包：

- `integrated`
- `内置-NinebotEnhance`
- `repacked`
- `fixed-debug-signed`
- 任何把模块 DEX、资源或配置写入九号 APK 后重新签名的包

修改 APK 内容后必须重新签名；没有九号官方私钥，无法保留九号官方签名。因此本项目不提供“修改内容但保持官方签名”的方案。

## 验证独立模块

```sh
python3 scripts/verify_runtime_module.py app/build/outputs/apk/debug/app-debug.apk
```

验证通过只说明 APK 是独立运行时模块，不代表九号服务器会接受被修改的运行环境。模块不应修改九号登录、Token、请求签名、完整性校验或证书校验逻辑。

## 设备安装顺序

1. 卸载内置模块的九号合包。
2. 安装官方九号原 APK。
3. 单独安装 `NinebotEnhance` 模块 APK。
4. 在 NPA/LSPosed 中启用模块，作用域只选择九号出行。
5. 强制停止并重新启动九号出行，重新登录后检查车辆信息。

模块 APK 自身的开发签名不等于九号 APK 的签名。服务器认证依赖九号 APK 时，必须核对九号 APK 本体的官方证书摘要。
