package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.aws.outputs.GetRegionResult;
import com.pulumi.core.Output;
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
        String igwName = config.require("igwName");

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

        // Generate Subnets
        var availabilityZones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());
        ctx.export("availabilityZones", availabilityZones.applyValue(GetAvailabilityZonesResult::names));

        availabilityZones.applyValue(getAvailabilityZonesResult -> {
            List<String> zones = getAvailabilityZonesResult.names();
            List<Subnet> publicSubnets=createPublicSubnets(zones,vpc,new ArrayList<>());
            List<Subnet> privateSubnets=createPrivateSubnets(zones,vpc,new ArrayList<>());
            RouteTable publicRouteTable = new RouteTable("publicRouteTable", RouteTableArgs.builder()
                    .vpcId(vpc.id())
                    .tags(Map.of("Name", "PublicRouteTable"))
                    .build());
            for(int i=0;i<publicSubnets.size();i++){
                new RouteTableAssociation("publicSubnetAssociation" + i, new RouteTableAssociationArgs.Builder()
                        .subnetId(publicSubnets.get(i).id())
                        .routeTableId(publicRouteTable.id())
                        .build());
            }

            // Create private route table and attach private subnets
            RouteTable privateRouteTable = new RouteTable("privateRouteTable", RouteTableArgs.builder()
                    .vpcId(vpc.id())
                    .tags(Map.of("Name", "PrivateRouteTable"))
                    .build());

            for(int i=0;i<privateSubnets.size();i++){
                new RouteTableAssociation("privateSubnetAssociation" + i, new RouteTableAssociationArgs.Builder()
                        .subnetId(privateSubnets.get(i).id())
                        .routeTableId(privateRouteTable.id())
                        .build());
            }

            // Create public route with the internet gateway as the target
            new Route("publicRoute", new RouteArgs.Builder()
                    .routeTableId(publicRouteTable.id())
                    .destinationCidrBlock("0.0.0.0/0")
                    .gatewayId(igw.id())
                    .build());

            ctx.export("sfd",Output.of(privateSubnets.size()));


            return null;
        });


        // Create public route table and attach public subnets

//        Output.of(publicSubnetIds.size());





    }

    private static List<Subnet> createPrivateSubnets(List<String> zones, Vpc vpc, List<Subnet> privateSubnets) {
        int num_of_subnets=Math.min(3,zones.size());
        for(int i=0;i<num_of_subnets;i++){
            Subnet privateSubnet = new Subnet("privateSubnet" + i, new SubnetArgs.Builder()
                    .vpcId(vpc.id())
                    .availabilityZone(zones.get(i))
                    .cidrBlock("10.0." + (i + 3) + ".0/24")
                    .tags(Map.of("Name", "PrivateSubnet" + i))
                    .build());

            privateSubnets.add(privateSubnet);
        }
        return privateSubnets;

    }

    private static List<Subnet> createPublicSubnets(List<String> zones, Vpc vpc, List<Subnet> publicSubnets) {
        int num_of_subnets=Math.min(3,zones.size());
        for (int i = 0; i < num_of_subnets; i++) {
            // Create public subnets
            Subnet publicSubnet = new Subnet("publicSubnet" + i, new SubnetArgs.Builder()
                    .vpcId(vpc.id())
                    .availabilityZone(zones.get(i))
                    .cidrBlock("10.0." + i + ".0/24")
                    .mapPublicIpOnLaunch(true)
                    .tags(Map.of("Name", "PublicSubnet" + i))
                    .build());
            publicSubnets.add(publicSubnet);
        }
        return publicSubnets;
    }


}
