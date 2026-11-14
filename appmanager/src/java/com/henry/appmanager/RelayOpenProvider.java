package com.henry.appmanager;

/**
 * 中继的「第三方授权」入口，逻辑全部继承自 {@link RelayProvider}。
 *
 * 为什么要单独一个类：一个 provider 类只能对应一个 &lt;provider&gt; 声明。
 * 同一个类声明两次（authority 不同）会报
 * `SecurityException: The authority ... does not match the one of the contentProvider`。
 *
 * 两个入口的区别只在于清单：
 *   - RelayProvider     ：带 signature 级权限，同签名应用之外的调用在 Binder 层就被拒；
 *   - RelayOpenProvider ：不带权限，靠 RelayGrant 的运行期名单（包名 + 签名指纹）放行。
 */
public class RelayOpenProvider extends RelayProvider {
}
