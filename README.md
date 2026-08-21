# OhMyAu

基于 **Myau** 客户端的开源 Minecraft 1.8.9 Forge 客户端模组。

## 特性

- 70+ 实用模块：KillAura、Scaffold、ESP、Speed、Fly、Blink、BackTrack、ChestStealer 等
- 内置命令系统：`.toggle`、`.bind`、`.friend`、`.vclip`、`.config` 等
- 可自由拖拽的 HUD 元素（模块列表、TargetHUD、Radar、Indicators 等）
- 账号管理器：微软账号 / Session / Token 登录与切换
- 基于 Mixin + Access Transformer，使用 Essential Loom 构建

## 构建

环境要求：JDK 8

```bash
./gradlew build
```

构建产物位于 `build/libs/`，将 jar 放入 `.minecraft/mods/` 即可使用。

## 致谢

- DeepSeekV4, GLM5.2
 
## License

[GNU General Public License v3.0](LICENSE)