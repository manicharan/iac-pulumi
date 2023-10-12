package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.ec2.Subnet;
import com.pulumi.aws.ec2.SubnetArgs;
import com.pulumi.aws.ec2.Vpc;
import com.pulumi.aws.ec2.VpcArgs;

import java.util.Map;

public class App {
    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    private static void stack(Context ctx) {
        var config = ctx.config();
        var main = new Vpc("mani", VpcArgs.builder()
                .cidrBlock("10.0.0.0/16")
                .instanceTenancy("default")
                .tags(Map.of("Name","mani"))
                .build());
        var subnet1 = new Subnet("main", SubnetArgs.builder()
                .vpcId(main.id())
                .cidrBlock("10.0.1.0/24")
                .tags(Map.of("Name", "mani"))
                .build());

//        var main = new Vpc("main", VpcArgs.builder()
//                .cidrBlock("10.0.0.0/16")
//                .instanceTenancy("default")
//                .tags(Map.of("Name", "main"))
//                .build());

    }
}
