# Bukkit overlay 构建

使用根目录 gradlew.bat 和 Java 25。这个入口只编译 Bukkit/API 源码，并用指定基础插件里的完整运行依赖组装 JAR，不解析 Fabric、Forge 或发布插件。

```powershell
.\gradlew.bat -p overlay-build clean check shadowJar `
  '-PvoicechatRuntimeJar=../runtime/voicechat-base.jar' `
  '-PvoicechatServerLibraries=D:/你的隔离服务端/libraries' `
  '-PvoicechatPlaceholderJar=D:/你的隔离服务端/plugins/PlaceholderAPI.jar'
```

基础插件必须与本次补丁的 API 分支一致。源码交付包 runtime/voicechat-base.jar 为验证用基础插件，其 SHA-256 为 E5191D421DC1A8FC721FD6BA1CB3E1061584AA35CACD7AB706485C1ACB08AD54。

输出：overlay-build/build/libs/voicechat-bukkit-2.6.21-hotload.3.jar。

check 包含 PluginEventSnapshotSelfTest 的 main-based 检查；本项目没有 JUnit 用例。生成目录先将源码依赖包名映射为基础插件已有的 shaded 包名，原源码不会因此改写。FallbackTranslations 将 en_us.json 和 zh_cn.json 合并，服务端常用提示优先使用中文；源码包包含两个语言资源。
