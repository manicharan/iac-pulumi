package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.InstanceEbsBlockDeviceArgs;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.aws.outputs.GetRegionResult;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.core.Output;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;

public class App {
    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    private static void stack(Context ctx) {

        var config = ctx.config();

        // Get the current AWS region
        var awsRegion = AwsFunctions.getRegion();
        ctx.export("region", awsRegion.applyValue(GetRegionResult::name));

        //Getting the configuration values
        String vpcName = config.require("vpcName");
        String cidr = config.require("cidrBlock");
        String igwName = config.require("internetGatewayName");
        String publicRT = config.require("publicRouteTable");
        String privateRt = config.require("privateRouteTable");
        String publicRtAssociation = config.require("publicRouteTableAssociation");
        String privateRtAssociation = config.require("privateRouteTableAssociation");
        String publicRouteAll = config.require("publicRoute");
        String destinationCidrPublic = config.require("destinationCidrPublic");
        int num_of_subnets = config.requireInteger("num_of_subnets");
        String[] allowedPorts =config.require("ports").split(",");
        int volume = config.requireInteger("volume");
        String amiId = config.require("amiId");
        String sshKeyName = config.require("sshKeyName");
        String securityGroupName = config.require("securityGroupName");
        String deviceName = config.require("deviceName");
        String instanceType = config.require("instanceType");
        String volumeType = config.require("volumeType");
        String ec2Name = config.require("ec2Name");
        String rdsSecurityGroupName = config.require("rdsSecurityGroupName");
        int databasePort = config.requireInteger("databasePort");

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

        // Create a Security Group
        var securityGroup = new SecurityGroup(securityGroupName, SecurityGroupArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name",securityGroupName))
                .build());

        // Adding ingress to allow traffic on ports
        for(String allowedPort: allowedPorts){
            int port=Integer.parseInt(allowedPort);
            var securityGroupRule = new SecurityGroupRule("allowAllOn "+port, SecurityGroupRuleArgs.builder()
                    .type("ingress")
                    .fromPort(port)
                    .toPort(port)
                    .protocol("tcp")
                    .securityGroupId(securityGroup.id())
                    .cidrBlocks(destinationCidrPublic)
                    .build());

        }

        // Create a Security Group for RDS Instances
        var rdsSecurityGroup = new SecurityGroup(rdsSecurityGroupName, SecurityGroupArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name",rdsSecurityGroupName))
                .build());

        var rdsSecurityGroupRule = new SecurityGroupRule("allow EC2 on "+databasePort, SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(3306)
                .toPort(3306)
                .protocol("tcp")
                .sourceSecurityGroupId(securityGroup.id())
                .securityGroupId(rdsSecurityGroup.id())
                .build());

        var outboundRule = new SecurityGroupRule("Outbound Rule", SecurityGroupRuleArgs.builder()
                .type("egress")
                .fromPort(0)
                .toPort(0)
                .protocol("-1")
                .securityGroupId(securityGroup.id())
                .cidrBlocks(destinationCidrPublic)
                .build());

        ParameterGroup dbParameterGroup = new ParameterGroup("rds-parameter-group", ParameterGroupArgs.builder()
                .family("mariadb10.6")
                .description("Custom parameter group for Mariadb")
                .tags(Collections.singletonMap("Name", "MyDBParameterGroup"))
                .build());

        String rdsInstanceIdentifier = "webdb";
        String rdsMasterUsername = "admin";
        String rdsMasterPassword = "password";
        String dbName = "webdb";
        String storageType = "gp2";
        int allocatedStorage = 20;
        String instanceClass = "db.t3.micro";


        var availabilityZones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());
        ctx.export("availabilityZones", availabilityZones.applyValue(GetAvailabilityZonesResult::names));

        availabilityZones.applyValue(getAvailabilityZonesResult -> {
            List<String> zones = getAvailabilityZonesResult.names();
            List<String> subnetCidrBlocks = calculateCidrBlocks(cidr, (int) Math.pow(2,zones.size()));
            List<Subnet> publicSubnets=createSubnets(num_of_subnets,zones,vpc,subnetCidrBlocks,true,0);
            List<Subnet> privateSubnets=createSubnets(num_of_subnets,zones,vpc,subnetCidrBlocks,false,zones.size()-1);

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

            // Create a subnet group for your RDS instance
            SubnetGroup dbSubnetGroup = new SubnetGroup("my-db-subnet-group1", SubnetGroupArgs.builder()
                    .subnetIds(Output.all(privateSubnets.stream().map(Subnet::id).collect(toList())))
                    .description("My RDS Subnet Group")
                    .name("my-db-subnet-group1")
                    .build());
            // Create the RDS instance
            com.pulumi.aws.rds.Instance rdsInstance = new com.pulumi.aws.rds.Instance("myRDSInstance", com.pulumi.aws.rds.InstanceArgs.builder()
                    .instanceClass(instanceClass)
                    .allocatedStorage(allocatedStorage)
                    .engine("mariadb")
                    .engineVersion("10.6.14")
                    .identifier(rdsInstanceIdentifier)
                    .username(rdsMasterUsername)
                    .password(rdsMasterPassword)
                    .skipFinalSnapshot(true)
                    .publiclyAccessible(false)
                    .multiAz(false)
                    .parameterGroupName(dbParameterGroup.name())
                    .dbName(dbName)
                    .port(databasePort)
                    .vpcSecurityGroupIds(rdsSecurityGroup.id().applyValue(Collections::singletonList))
                    .dbSubnetGroupName(dbSubnetGroup.name())
                    .tags(Collections.singletonMap("Name", "MyRDSInstance"))
                    .build());

            Output<String> userDataScript =rdsInstance.address().applyValue(v -> String.format(
                    "#!/bin/bash\n" +
                            "echo 'export DB_User=%s' >> /etc/environment\n" +
                            "echo 'export DB_Password=%s' >> /etc/environment\n" +
                            "echo 'export DB_Host=%s' >> /etc/environment\n" +
                            "echo 'export DB_Port=%s' >> /etc/environment\n"+
                            "echo 'export DB_Database=%s' >> /etc/environment\n",

                            rdsMasterUsername, rdsMasterPassword,v,databasePort,dbName
            ));

            // Create EC2 instance
            var instance = new Instance(ec2Name, InstanceArgs.builder()
                    .ami(amiId)
                    .instanceType(instanceType)
                    .keyName(sshKeyName)
                    .ebsBlockDevices(InstanceEbsBlockDeviceArgs.builder()
                            .deleteOnTermination(true)
                            .deviceName(deviceName)
                            .volumeType(volumeType)
                            .volumeSize(volume)
                            .build())
                    .vpcSecurityGroupIds(securityGroup.id().applyValue(Collections::singletonList))
                    .subnetId(publicSubnets.get(0).id())
                    .disableApiTermination(false)
                    .userData(userDataScript)
                    .tags(Map.of("Name",ec2Name))
                    .build());

            var instance1 = new Instance("webapp2", InstanceArgs.builder()
                    .ami(amiId)
                    .instanceType(instanceType)
                    .keyName(sshKeyName)
                    .ebsBlockDevices(InstanceEbsBlockDeviceArgs.builder()
                            .deleteOnTermination(true)
                            .deviceName(deviceName)
                            .volumeType(volumeType)
                            .volumeSize(volume)
                            .build())
                    .vpcSecurityGroupIds(securityGroup.id().applyValue(Collections::singletonList))
                    .subnetId(publicSubnets.get(0).id())
                    .disableApiTermination(false)
                    .userData(userDataScript)
                    .tags(Map.of("Name","webapp2"))
                    .build());

            return null;
        });
    }

    private static List<String> calculateCidrBlocks(String cidr, int num) {
        List<String> subnetCidrBlocks = new ArrayList<>();
        String[] parts = cidr.split("/");
        int subnetPrefix = Integer.parseInt(parts[1]) + (int) (Math.log(num) / Math.log(2));
        String[] part = parts[0].split("\\.");

        for (int i = 0; i < num; i++) {
            String subnetIp = String.format("%s.%s.%d.%s", part[0], part[1],(i * (256/num)),part[3]);
            subnetCidrBlocks.add(subnetIp + '/' + subnetPrefix);
        }

        return subnetCidrBlocks;
    }



    private static List<Subnet> createSubnets(int num,List<String> zones, Vpc vpc, List<String> subnetCidrBlocks, Boolean isPublic, int n) {
        int num_of_subnets=Math.min(num,zones.size());
        String subnetName = isPublic ? "Public" : "Private";
        List<Subnet> subnets = new ArrayList<>();

        for (int i = 0; i < num_of_subnets; i++) {
            // Create public subnets
            Subnet subnet = new Subnet(subnetName + i, new SubnetArgs.Builder()
                    .vpcId(vpc.id())
                    .availabilityZone(zones.get(i))
                    .cidrBlock(subnetCidrBlocks.get(i+n))
                    .mapPublicIpOnLaunch(isPublic)
                    .tags(Map.of("Name", subnetName + i))
                    .build());
            subnets.add(subnet);
        }
        return subnets;
    }
}
