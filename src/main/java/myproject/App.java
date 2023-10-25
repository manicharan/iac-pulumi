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
        String inputCidr = config.require("cidrBlock");
        String igwName = config.require("internetGatewayName");
        String publicRT = config.require("publicRouteTable");
        String privateRT = config.require("privateRouteTable");
        String publicRtAssociation = config.require("publicRouteTableAssociation");
        String privateRtAssociation = config.require("privateRouteTableAssociation");
        String publicRouteAllowAll = config.require("publicRoute");
        String destinationCidrPublic = config.require("destinationCidrPublic");
        int num_of_subnets = config.requireInteger("num_of_subnets");
        String[] allowedPortsForEC2 =config.require("ports").split(",");
        int ec2Volume = config.requireInteger("volume");
        String amiId = config.require("amiId");
        String sshKeyName = config.require("sshKeyName");
        String ec2SecurityGroupName = config.require("securityGroupName");
        String ec2DeviceName = config.require("deviceName");
        String ec2InstanceType = config.require("instanceType");
        String ec2VolumeType = config.require("volumeType");
        String ec2Name = config.require("ec2Name");
        String rdsSecurityGroupName = config.require("rdsSecurityGroupName");
        int databasePort = config.requireInteger("databasePort");
        String rdsInstanceIdentifier = config.require("instanceIdentifier");
        String rdsUsername = config.require("rdsUsername");
        String rdsPassword = config.require("rdsPassword");
        String rdsDBName = config.require("rdsDBName");
        int rdsAllocatedStorage = config.requireInteger("rdsAllocatedStorage");
        String rdsInstanceClass = config.require("rdsInstanceClass");
        String rdsDBFamily = config.require("rdsDBFamily");
        String rdsEngine = config.require("rdsEngine");
        String rdsEngineVersion = config.require("rdsEngineVersion");

        // Create a VPC
        var vpc = new Vpc(vpcName, VpcArgs.builder()
                .cidrBlock(inputCidr)
                .instanceTenancy("default")
                .tags(Map.of("Name", vpcName))
                .build());

        // Create an Internet Gateway and attach VPC to it
        var igw = new InternetGateway(igwName, new InternetGatewayArgs.Builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", igwName))
                .build());

        // Create public route table
        RouteTable publicRouteTable = new RouteTable(publicRT, RouteTableArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", publicRT))
                .build());

        // Create public route with the internet gateway as the target
        new Route(publicRouteAllowAll, new RouteArgs.Builder()
                .routeTableId(publicRouteTable.id())
                .destinationCidrBlock(destinationCidrPublic)
                .gatewayId(igw.id())
                .build());

        // Create private route table
        RouteTable privateRouteTable = new RouteTable(privateRT, RouteTableArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", privateRT))
                .build());

        // Create a Security Group for EC2
        var securityGroupForEC2 = new SecurityGroup(ec2SecurityGroupName, SecurityGroupArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name",ec2SecurityGroupName))
                .build());

        // Adding ingress to allow traffic on ports
        for(String allowedPort: allowedPortsForEC2){
            int port=Integer.parseInt(allowedPort);
            var securityGroupRule = new SecurityGroupRule("allowAllOn "+port, SecurityGroupRuleArgs.builder()
                    .type("ingress")
                    .fromPort(port)
                    .toPort(port)
                    .protocol("tcp")
                    .securityGroupId(securityGroupForEC2.id())
                    .cidrBlocks(destinationCidrPublic)
                    .build());
        }

        // Create a Security Group for RDS Instances
        var rdsSecurityGroup = new SecurityGroup(rdsSecurityGroupName, SecurityGroupArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name",rdsSecurityGroupName))
                .build());

        // RDS Security Group rule to allow Inbound traffic from EC2 security group
        var rdsSecurityGroupRule = new SecurityGroupRule("allow EC2 on "+databasePort, SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(databasePort)
                .toPort(databasePort)
                .protocol("tcp")
                .sourceSecurityGroupId(securityGroupForEC2.id())
                .securityGroupId(rdsSecurityGroup.id())
                .build());

        // Outbound rule to allow outbound traffic for EC2
        var outboundRule = new SecurityGroupRule("Outbound Rule", SecurityGroupRuleArgs.builder()
                .type("egress")
                .fromPort(databasePort)
                .toPort(databasePort)
                .protocol("tcp")
                .securityGroupId(securityGroupForEC2.id())
                .cidrBlocks(destinationCidrPublic)
                .build());

        // Create DB Parameter group
        ParameterGroup rdsDBParameterGroup = new ParameterGroup("rdsgroup", ParameterGroupArgs.builder()
                .family(rdsDBFamily)
                .tags(Map.of("Name","rdsgroup"))
                .build());

        // Get availability zones
        var availabilityZones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());
        ctx.export("availabilityZones", availabilityZones.applyValue(GetAvailabilityZonesResult::names));

        availabilityZones.applyValue(getAvailabilityZonesResult -> {
            List<String> zones = getAvailabilityZonesResult.names();
            List<String> subnetCidrBlocks = calculateCidrBlocks(inputCidr, (int) Math.pow(2,zones.size()));
            List<Subnet> publicSubnets=createSubnets(num_of_subnets,zones,vpc,subnetCidrBlocks,true,0);
            List<Subnet> privateSubnets=createSubnets(num_of_subnets,zones,vpc,subnetCidrBlocks,false,zones.size()-1);

            // Attaching public subnets to public route table
            for(int i=0;i<publicSubnets.size();i++){
                new RouteTableAssociation(publicRtAssociation + i,RouteTableAssociationArgs.builder()
                        .subnetId(publicSubnets.get(i).id())
                        .routeTableId(publicRouteTable.id())
                        .build());
            }

            // Attaching private subnets to private route table
            for(int i=0;i<privateSubnets.size();i++){
                new RouteTableAssociation(privateRtAssociation + i, RouteTableAssociationArgs.builder()
                        .subnetId(privateSubnets.get(i).id())
                        .routeTableId(privateRouteTable.id())
                        .build());
            }

            // Create a subnet group for your RDS instance
            SubnetGroup dbSubnetGroup = new SubnetGroup("subnetgroup", SubnetGroupArgs.builder()
                    .subnetIds(Output.all(privateSubnets.stream().map(Subnet::id).collect(toList())))
                    .name("subnetgroup")
                    .build());

            // Create the RDS instance
            com.pulumi.aws.rds.Instance rdsInstance = new com.pulumi.aws.rds.Instance("myRDSInstance", com.pulumi.aws.rds.InstanceArgs.builder()
                    .instanceClass(rdsInstanceClass)
                    .allocatedStorage(rdsAllocatedStorage)
                    .engine(rdsEngine)
                    .engineVersion(rdsEngineVersion)
                    .identifier(rdsInstanceIdentifier)
                    .username(rdsUsername)
                    .password(rdsPassword)
                    .skipFinalSnapshot(true)
                    .publiclyAccessible(false)
                    .multiAz(false)
                    .parameterGroupName(rdsDBParameterGroup.name())
                    .dbName(rdsDBName)
                    .port(databasePort)
                    .vpcSecurityGroupIds(rdsSecurityGroup.id().applyValue(Collections::singletonList))
                    .dbSubnetGroupName(dbSubnetGroup.name())
                    .tags(Map.of("Name","myRDSInstance"))
                    .build());

            // User Data Script
            Output<String> userDataScript =rdsInstance.address().applyValue(v -> String.format(
                    "#!/bin/bash\n" +
                            "echo 'export DB_User=%s' >> /opt/csye6225/application.properties\n" +
                            "echo 'export DB_Password=%s' >> /opt/csye6225/application.properties\n" +
                            "echo 'export DB_Host=%s' >> /opt/csye6225/application.properties\n" +
                            "echo 'export DB_Port=%s' >> /opt/csye6225/application.properties\n"+
                            "echo 'export DB_Database=%s' >> /opt/csye6225/application.properties\n",

                            rdsUsername, rdsPassword,v,databasePort,rdsDBName
            ));

            // Create EC2 instance
            var instance = new Instance(ec2Name, InstanceArgs.builder()
                    .ami(amiId)
                    .instanceType(ec2InstanceType)
                    .keyName(sshKeyName)
                    .ebsBlockDevices(InstanceEbsBlockDeviceArgs.builder()
                            .deleteOnTermination(true)
                            .deviceName(ec2DeviceName)
                            .volumeType(ec2VolumeType)
                            .volumeSize(ec2Volume)
                            .build())
                    .vpcSecurityGroupIds(securityGroupForEC2.id().applyValue(Collections::singletonList))
                    .subnetId(publicSubnets.get(0).id())
                    .disableApiTermination(false)
                    .userData(userDataScript)
                    .tags(Map.of("Name",ec2Name))
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