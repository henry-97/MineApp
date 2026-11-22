package com.henry.appmanager;

/** 由 tools/gen_assets.py 从 frontend.py 的 AUTO_LANG 自动生成，请勿手改。 */
public final class BuildCfg {
    /** 「自动」档固定用的名称语言：en = 英文名，zh = 中文名。发布时改 frontend.py */
    public static final String AUTO_LANG = "en";

    /** true = 迁移包（build.sh mig1 / mig2）：启动后自动把用户搬到 .next */
    public static final boolean MIGRATE = false;

    private BuildCfg() { }
}
