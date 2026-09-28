import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 生成 seed.sql 里演示账号的 BCrypt 哈希。
 *
 * <p><b>为什么要有这个工具</b>：BCrypt 的盐是随机的，哈希不能手写，只能算。
 * 与其让下一个人去猜「这串 $2a$10$... 是哪个密码」，不如把生成过程固化成可重跑的一行命令。
 *
 * <p><b>为什么不用 Python / htpasswd</b>：它们产出的是 {@code $2b$} / {@code $2y$} 前缀，
 * Spring 的 BCrypt 虽然也能校验，但既然这个哈希是写给这个项目用的，
 * 就直接用项目运行时那一套算法，省掉「前缀兼容性」这个可怀疑点。
 *
 * <p><b>为什么不用 jshell</b>（2026-09-28 实测踩到）：jshell 会**吞掉**异常并继续往下执行。
 * 本次 classpath 少了 commons-logging，{@code new BCryptPasswordEncoder()} 抛
 * NoClassDefFoundError，但 jshell 只打印一行错误就继续，于是
 * {@code hash} 保持 null、自检那行还照样打印 "SELFCHECK=OK" —— 一个彻头彻尾的假绿。
 * 换成编译执行后，异常会让进程真的以非 0 退出。
 *
 * <p>用法（Windows 的 classpath 分隔符是分号；jar 路径按本机 m2 仓库实际版本填）：
 * <pre>
 * java -cp "&lt;spring-security-crypto.jar&gt;;&lt;spring-jcl.jar&gt;" tools/GenBcrypt.java demo123456
 * </pre>
 * 不传参数时默认生成 demo123456。
 */
public final class GenBcrypt {

    public static void main(String[] args) {
        String plain = args.length > 0 ? args[0] : "demo123456";

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String hash = encoder.encode(plain);
        if (hash == null) {
            throw new IllegalStateException("encode() 返回了 null —— 环境有问题，不要把这个结果写进 seed.sql");
        }

        // 生成完立刻验一遍：防止把一个打错的哈希写进种子数据
        if (!encoder.matches(plain, hash)) {
            throw new IllegalStateException("自检失败：刚生成的哈希验证不通过，拒绝输出");
        }

        System.out.println("PLAIN=" + plain);
        System.out.println("HASH=" + hash);
        System.out.println("LEN=" + hash.length());
        System.out.println("SELFCHECK=OK");
    }
}
