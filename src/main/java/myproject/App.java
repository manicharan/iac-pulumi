package myproject;

import com.pulumi.Config;
import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.aws.outputs.GetRegionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class App {
    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    private static void stack(Context ctx) {

        var config = ctx.config();

        // Get the current AWS region
        var awsRegion = AwsFunctions.getRegion();
        ctx.export("region", awsRegion.applyValue(GetRegionResult::name));

        String vpcName = config.require("vpcName");
        String cidr = config.require("cidrBlock");
        String igwName = config.require("internetGatewayName");
        String publicRT = config.require("publicRouteTable");
        String privateRt = config.require("privateRouteTable");
        String publicRtAssociation = config.require("publicRouteTableAssociation");
        String privateRtAssociation = config.require("privateRouteTableAssociation");
        String publicRouteAll = config.require("publicRoute");
        String destinationCidrPublic = config.require("destinationCidrPublic");
        int num_of_subnets = Integer.parseInt(config.require("num_of_subnets"));

        // Create a VPC
        var vpc = new Vpc(vpcName, VpcArgs.builder()
                .cidrBlock(cidr)
                .instanceTenancy("default")
                .tags(Map.of("Name", vpcName))
                .build());

        // Create an Internet Gateway and attach it to the VPC
        var igw = new InternetGateway(igwName, new InternetGatewayArgs.Builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", igwName))
                .build());

        // Create Subnets
        var availabilityZones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());
        ctx.export("availabilityZones", availabilityZones.applyValue(GetAvailabilityZonesResult::names));

        availabilityZones.applyValue(getAvailabilityZonesResult -> {
            List<String> zones = getAvailabilityZonesResult.names();
            List<Subnet> publicSubnets=createPublicSubnets(num_of_subnets,zones,vpc,config,new ArrayList<>());
            List<Subnet> privateSubnets=createPrivateSubnets(num_of_subnets,zones,vpc,config,new ArrayList<>());

            // Create public route table and attach public subnets
            RouteTable publicRouteTable = new RouteTable(publicRT, RouteTableArgs.builder()
                    .vpcId(vpc.id())
                    .tags(Map.of("Name", publicRT))
                    .build());

            for(int i=0;i<publicSubnets.size();i++){
                new RouteTableAssociation(publicRtAssociation + i,RouteTableAssociationArgs.builder()
                        .subnetId(publicSubnets.get(i).id())
                        .routeTableId(publicRouteTable.id())
                        .build());
            }

            // Create private route table and attach private subnets
            RouteTable privateRouteTable = new RouteTable(privateRt, RouteTableArgs.builder()
                    .vpcId(vpc.id())
                    .tags(Map.of("Name", privateRt))
                    .build());

            for(int i=0;i<privateSubnets.size();i++){
                new RouteTableAssociation(privateRtAssociation + i, RouteTableAssociationArgs.builder()
                        .subnetId(privateSubnets.get(i).id())
                        .routeTableId(privateRouteTable.id())
                        .build());
            }

            // Create public route with the internet gateway as the target
            new Route(publicRouteAll, new RouteArgs.Builder()
                    .routeTableId(publicRouteTable.id())
                    .destinationCidrBlock(destinationCidrPublic)
                    .gatewayId(igw.id())
                    .build());

            return null;
        });
    }

    private static List<Subnet> createPrivateSubnets(int num,List<String> zones, Vpc vpc, Config config, List<Subnet> privateSubnets) {
        int num_of_subnets=Math.min(num,zones.size());
        String privateSubnetName = config.require("privateSubnetName");

        for(int i=0;i<num_of_subnets;i++){
            Subnet privateSubnet = new Subnet(privateSubnetName + i, new SubnetArgs.Builder()
                    .vpcId(vpc.id())
                    .availabilityZone(zones.get(i))
                    .cidrBlock("10.0." + (i + 4) + ".0/24")
                    .tags(Map.of("Name", privateSubnetName + i))
                    .build());

            privateSubnets.add(privateSubnet);
        }
        return privateSubnets;

    }

    private static List<Subnet> createPublicSubnets(int num,List<String> zones, Vpc vpc, Config config, List<Subnet> publicSubnets) {
        int num_of_subnets=Math.min(num,zones.size());
        String publicSubnetName = config.require("publicSubnetName");

        for (int i = 0; i < num_of_subnets; i++) {
            // Create public subnets
            Subnet publicSubnet = new Subnet(publicSubnetName + i, new SubnetArgs.Builder()
                    .vpcId(vpc.id())
                    .availabilityZone(zones.get(i))
                    .cidrBlock("10.0." + (i+1) + ".0/24")
                    .mapPublicIpOnLaunch(true)
                    .tags(Map.of("Name", publicSubnetName + i))
                    .build());
            publicSubnets.add(publicSubnet);
        }
        return publicSubnets;
    }
}
