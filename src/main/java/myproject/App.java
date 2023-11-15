package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.autoscaling.Attachment;
import com.pulumi.aws.autoscaling.AttachmentArgs;
import com.pulumi.aws.autoscaling.Group;
import com.pulumi.aws.autoscaling.GroupArgs;
import com.pulumi.aws.autoscaling.inputs.GroupLaunchTemplateArgs;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.iam.*;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementArgs;
import com.pulumi.aws.iam.inputs.GetPolicyDocumentStatementPrincipalArgs;
import com.pulumi.aws.iam.outputs.GetPolicyDocumentResult;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.lb.*;
import com.pulumi.aws.lb.inputs.ListenerDefaultActionArgs;
import com.pulumi.aws.lb.inputs.TargetGroupHealthCheckArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.aws.outputs.GetRegionResult;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.core.Output;

import java.util.*;

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
        String[] allowedPortsForEC2 = config.require("allowedPortsForEC2").split(",");
        int ec2Volume = config.requireInteger("volume");
        String amiId = config.require("amiId");
        String sshKeyName = config.require("sshKeyName");
        String ec2SecurityGroupName = config.require("ec2SecurityGroupName");
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
        String instanceAssumeRoleIdentifier = config.require("instanceAssumeRoleIdentifier");
        String CWRoleName = config.require("CWRoleName");
        String ServerAgentPolicyARN = config.require("ServerAgentPolicyARN");
        String domainZoneId = config.require("domainZoneId");
        String domainName = config.require("domainName");
        String lbSecurityGroupName = config.require("lbSecurityGroupName");
        String[] allowedPortsForLB = config.require("allowedPortsForLB").split(",");
        String[] outboundPortsForEC2 = config.require("outboundPortsForEC2").split(",");

        // Create a VPC
        var vpc = new Vpc(vpcName, VpcArgs.builder()
                .cidrBlock(inputCidr)
                .instanceTenancy("default")
                .tags(Map.of("Name", vpcName))
                .build());
        //write outbound rule for load balancer

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

        // Create a security Group for Load Balancer
        var securityGroupForLB = new SecurityGroup(lbSecurityGroupName, SecurityGroupArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", lbSecurityGroupName))
                .build());

        // Create a Security Group for EC2
        var securityGroupForEC2 = new SecurityGroup(ec2SecurityGroupName, SecurityGroupArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", ec2SecurityGroupName))
                .build());

        // Adding ingress and egress to allow traffic for Load Balancer Security Group
        for (String allowedPort : allowedPortsForLB) {
            int port = Integer.parseInt(allowedPort);
            var securityGroupRule = new SecurityGroupRule("InboundRuleForLBOn " + port, SecurityGroupRuleArgs.builder()
                    .type("ingress")
                    .fromPort(port)
                    .toPort(port)
                    .protocol("tcp")
                    .securityGroupId(securityGroupForLB.id())
                    .cidrBlocks(destinationCidrPublic)
                    .build());
            var outboundRule = new SecurityGroupRule("OutboundRuleForLBOn " + port, SecurityGroupRuleArgs.builder()
                    .type("egress")
                    .fromPort(port)
                    .toPort(port)
                    .protocol("tcp")
                    .securityGroupId(securityGroupForLB.id())
                    .cidrBlocks(destinationCidrPublic)
                    .build());
        }

        //adding egress for LB on port 8080
        var outboundRuleforLB = new SecurityGroupRule("OutboundRuleForLBOn "+8080, SecurityGroupRuleArgs.builder()
                .type("egress")
                .fromPort(8080)
                .toPort(8080)
                .protocol("tcp")
                .securityGroupId(securityGroupForLB.id())
                .cidrBlocks(destinationCidrPublic)
                .build());

        // all traffic for EC2 on port 22
        var securityGroupRuleAll = new SecurityGroupRule("AllInboundRuleForEC2On " + 22, SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(22)
                .toPort(22)
                .protocol("tcp")
                .securityGroupId(securityGroupForEC2.id())
                .cidrBlocks(destinationCidrPublic)
                .build());

        // Adding ingress and egress to allow traffic for Application Security Group
        for (String allowedPort : allowedPortsForEC2) {
            int port = Integer.parseInt(allowedPort);
            var securityGroupRule = new SecurityGroupRule("InboundRuleForEC2On " + port, SecurityGroupRuleArgs.builder()
                    .type("ingress")
                    .fromPort(port)
                    .toPort(port)
                    .protocol("tcp")
                    .sourceSecurityGroupId(securityGroupForLB.id())
                    .securityGroupId(securityGroupForEC2.id())
                    .build());
        }


        // Create a Security Group for RDS Instances
        var rdsSecurityGroup = new SecurityGroup(rdsSecurityGroupName, SecurityGroupArgs.builder()
                .vpcId(vpc.id())
                .tags(Map.of("Name", rdsSecurityGroupName))
                .build());

        // RDS Security Group rule to allow Inbound traffic from EC2 security group
        var rdsSecurityGroupRule = new SecurityGroupRule("InboundRuleForEC2On " + databasePort, SecurityGroupRuleArgs.builder()
                .type("ingress")
                .fromPort(databasePort)
                .toPort(databasePort)
                .protocol("tcp")
                .sourceSecurityGroupId(securityGroupForEC2.id())
                .securityGroupId(rdsSecurityGroup.id())
                .build());

        // Outbound rule to allow outbound traffic for EC2
        for (String allowedPort : outboundPortsForEC2) {
            int port = Integer.parseInt(allowedPort);
            var outboundRule = new SecurityGroupRule("OutboundRuleForEC2On " + port, SecurityGroupRuleArgs.builder()
                    .type("egress")
                    .fromPort(port)
                    .toPort(port)
                    .protocol("tcp")
                    .securityGroupId(securityGroupForEC2.id())
                    .cidrBlocks(destinationCidrPublic)
                    .build());
        }

        // Create DB Parameter group
        ParameterGroup rdsDBParameterGroup = new ParameterGroup("rdsgroup", ParameterGroupArgs.builder()
                .family(rdsDBFamily)
                .tags(Map.of("Name", "rdsgroup"))
                .build());

        // Get availability zones
        var availabilityZones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());
        ctx.export("availabilityZones", availabilityZones.applyValue(GetAvailabilityZonesResult::names));

        availabilityZones.applyValue(getAvailabilityZonesResult -> {
            List<String> zones = getAvailabilityZonesResult.names();
            List<String> subnetCidrBlocks = calculateCidrBlocks(inputCidr, (int) Math.pow(2, zones.size()));
            List<Subnet> publicSubnets = createSubnets(num_of_subnets, zones, vpc, subnetCidrBlocks, true, 0);
            List<Subnet> privateSubnets = createSubnets(num_of_subnets, zones, vpc, subnetCidrBlocks, false, zones.size() - 1);

            // Attaching public subnets to public route table
            for (int i = 0; i < publicSubnets.size(); i++) {
                new RouteTableAssociation(publicRtAssociation + i, RouteTableAssociationArgs.builder()
                        .subnetId(publicSubnets.get(i).id())
                        .routeTableId(publicRouteTable.id())
                        .build());
            }

            // Attaching private subnets to private route table
            for (int i = 0; i < privateSubnets.size(); i++) {
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
                    .tags(Map.of("Name", "myRDSInstance"))
                    .build());

            // User Data Script
            Output<String> userDataScript = rdsInstance.address().applyValue(v -> String.format(
                    "#!/bin/bash\n" +
                            "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/opt/cloudwatch-config.json -s\n" +
                            "echo 'export DB_User=%s' >> /opt/csye6225/application.properties\n" +
                            "echo 'export DB_Password=%s' >> /opt/csye6225/application.properties\n" +
                            "echo 'export DB_Host=%s' >> /opt/csye6225/application.properties\n" +
                            "echo 'export DB_Port=%s' >> /opt/csye6225/application.properties\n" +
                            "echo 'export DB_Database=%s' >> /opt/csye6225/application.properties\n",
                    rdsUsername, rdsPassword, v, databasePort, rdsDBName
            ));

            Output<String> encodedUserData = userDataScript.applyValue(data -> {
                return Base64.getEncoder().encodeToString(data.getBytes());
            });

            //creating an assumeRolePolicy for EC2 instance
            final var instanceAssumeRolePolicy = IamFunctions.getPolicyDocument(GetPolicyDocumentArgs.builder()
                    .statements(GetPolicyDocumentStatementArgs.builder()
                            .effect("Allow")
                            .principals(GetPolicyDocumentStatementPrincipalArgs.builder()
                                    .type("Service")
                                    .identifiers(instanceAssumeRoleIdentifier)
                                    .build())
                            .actions("sts:AssumeRole")
                            .build())
                    .build());

            //creating a role
            var cwRole = new Role(CWRoleName, RoleArgs.builder()
                    .assumeRolePolicy(instanceAssumeRolePolicy.applyValue(GetPolicyDocumentResult::json))
                    .managedPolicyArns(ServerAgentPolicyARN)
                    .build());

            //creating instance profile for role
            var instanceProfile = new InstanceProfile("instanceProfile", InstanceProfileArgs.builder()
                    .role(cwRole.id())
                    .build());

            var launchTemplateForEC2 = new LaunchTemplate("testTemplateApp", LaunchTemplateArgs.builder()
                    .imageId(amiId)
                    .instanceType(ec2InstanceType)
                    .keyName(sshKeyName)
                    .blockDeviceMappings(LaunchTemplateBlockDeviceMappingArgs.builder()
                            .deviceName(ec2DeviceName)
                            .ebs(LaunchTemplateBlockDeviceMappingEbsArgs.builder()
                                    .deleteOnTermination("true")
                                    .volumeType(ec2VolumeType)
                                    .volumeSize(ec2Volume)
                                    .build())
                            .build())
                    .vpcSecurityGroupIds(securityGroupForEC2.id().applyValue(Collections::singletonList))
                    .disableApiTermination(false)
                    .iamInstanceProfile(LaunchTemplateIamInstanceProfileArgs.builder()
                            .arn(instanceProfile.arn())
                            .build())//can attach network interface here
                    .metadataOptions(LaunchTemplateMetadataOptionsArgs.builder()
                            .instanceMetadataTags("enabled")
                            .build())
                    .userData(encodedUserData)
                    .tags(Map.of("Name","testTemplateApp"))
                    .tagSpecifications(LaunchTemplateTagSpecificationArgs.builder()
                            .resourceType("instance")
                            .tags(Map.of("Name","webapp"))
                            .build())
                    .build());

//            List<String> vpcZoneIdentifiers = publicSubnets.stream()
//                    .map(Subnet::getId)
//                    .collect(Collectors.toList());
//            String vpcZoneIdentifier = .stream()
//                    .map(Subnet::getId)
//                    .collect(Collectors.joining(","));

            var asg = new Group("autoScalingGroup", GroupArgs.builder()
                    .minSize(1)
                    .maxSize(3)
                    .desiredCapacity(1)
                    .defaultCooldown(60)
                    .defaultInstanceWarmup(120)
                    .launchTemplate(GroupLaunchTemplateArgs.builder()
                            .name(launchTemplateForEC2.name())
                            .version("$Latest")
                            .build())
                    .vpcZoneIdentifiers(Output.all(publicSubnets.stream()
                            .map(Subnet::id)
                            .collect(toList()))
                            )
                    .build());

//            var scaleUpPolicy = new Policy("scaleUp", PolicyArgs.builder()
//                    .autoscalingGroupName(asg.name())
//                    .stepAdjustments()
//                    .policyType()
//                    .targetTrackingConfiguration(PolicyTargetTrackingConfigurationArgs.builder()
//                            .predefinedMetricSpecification(PolicyTargetTrackingConfigurationPredefinedMetricSpecificationArgs.builder()
//                                    .predefinedMetricType()
//                                    .
//                                    .build())
//                            .targetValue()
//                            .build())
//                    .build());

            var loadBalancer = new LoadBalancer("LoadBalancerForEC2", LoadBalancerArgs.builder()
                    .loadBalancerType("application")
                    .securityGroups(securityGroupForLB.id().applyValue(Collections::singletonList))
                    .subnets(Output.all(publicSubnets.stream()
                            .map(Subnet::id)
                            .collect(toList()))
                            )
                    .build());

//            var targetGroup = new TargetGroup("targetGroup", TargetGroupArgs.builder()
//                    .port(8080)
//                    .protocol("HTTP")
//                    .vpcId(vpc.id())
//                    .build());
            var targetGroup = new TargetGroup("targetGroupForLB", new TargetGroupArgs.Builder()
                    .port(8080)
                    .protocol("HTTP")
                    .vpcId(vpc.id())
                    .healthCheck(TargetGroupHealthCheckArgs.builder()
                            .enabled(true)
                            .interval(30)
                            .port("8080")
                            .path("/healthz")
                            .protocol("HTTP").build())
                    .build());
//
//
            var listener = new Listener("listener", ListenerArgs.builder()
                    .loadBalancerArn(loadBalancer.arn())
                    .port(80)
                    .defaultActions(ListenerDefaultActionArgs.builder()
                            .type("forward")
                            .targetGroupArn(targetGroup.arn())
                            .build())
                    .build());
////
            var attachment = new Attachment("autoscaleattachment", AttachmentArgs.builder()
                    .autoscalingGroupName(asg.name())
                    .lbTargetGroupArn(targetGroup.arn())
                    .build());

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
                    .iamInstanceProfile(instanceProfile.id())
                    .tags(Map.of("Name", ec2Name))
                    .build());

//            //eip association
//            var eip = new Eip("eip", EipArgs.builder()
//                    .domain("vpc")
//                    .build());
//            var eipAssociation = new EipAssociation("eipass", EipAssociationArgs.builder()
//                    .instanceId(instance.id())
//                    .allocationId(eip.id())
//                    .build());

            //creating A Record
//            var record = new Record("www", RecordArgs.builder()
//                    .zoneId(domainZoneId)
//                    .name(domainName)
//                    .type("A")
//                    .ttl(60)
//                    .records(instance.publicIp().applyValue((Collections::singletonList)))
//                    .build());

            return null;
        });
    }

    private static List<String> calculateCidrBlocks(String cidr, int num) {
        List<String> subnetCidrBlocks = new ArrayList<>();
        String[] parts = cidr.split("/");
        int subnetPrefix = Integer.parseInt(parts[1]) + (int) (Math.log(num) / Math.log(2));
        String[] part = parts[0].split("\\.");

        for (int i = 0; i < num; i++) {
            String subnetIp = String.format("%s.%s.%d.%s", part[0], part[1], (i * (256 / num)), part[3]);
            subnetCidrBlocks.add(subnetIp + '/' + subnetPrefix);
        }

        return subnetCidrBlocks;
    }

    private static List<Subnet> createSubnets(int num, List<String> zones, Vpc vpc, List<String> subnetCidrBlocks, Boolean isPublic, int n) {
        int num_of_subnets = Math.min(num, zones.size());
        String subnetName = isPublic ? "Public" : "Private";
        List<Subnet> subnets = new ArrayList<>();

        for (int i = 0; i < num_of_subnets; i++) {
            // Create public subnets
            Subnet subnet = new Subnet(subnetName + i, new SubnetArgs.Builder()
                    .vpcId(vpc.id())
                    .availabilityZone(zones.get(i))
                    .cidrBlock(subnetCidrBlocks.get(i + n))
                    .mapPublicIpOnLaunch(isPublic)
                    .tags(Map.of("Name", subnetName + i))
                    .build());
            subnets.add(subnet);
        }
        return subnets;
    }
}