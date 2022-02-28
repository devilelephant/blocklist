package com.devilelephant;

import static java.util.Collections.singletonList;
import static software.amazon.awscdk.core.BundlingOutput.ARCHIVED;
import static software.amazon.awscdk.services.lambda.FileSystem.fromEfsAccessPoint;

import java.util.List;
import software.amazon.awscdk.core.BundlingOptions;
import software.amazon.awscdk.core.CfnOutput;
import software.amazon.awscdk.core.CfnOutputProps;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.DockerVolume;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.apigatewayv2.AddRoutesOptions;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.HttpApiProps;
import software.amazon.awscdk.services.apigatewayv2.HttpMethod;
import software.amazon.awscdk.services.apigatewayv2.PayloadFormatVersion;
import software.amazon.awscdk.services.apigatewayv2.integrations.LambdaProxyIntegration;
import software.amazon.awscdk.services.apigatewayv2.integrations.LambdaProxyIntegrationProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.efs.AccessPoint;
import software.amazon.awscdk.services.efs.AccessPointOptions;
import software.amazon.awscdk.services.efs.Acl;
import software.amazon.awscdk.services.efs.FileSystem;
import software.amazon.awscdk.services.efs.PosixUser;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.assets.AssetOptions;

public class InfrastructureStack extends Stack {

  // per docs must start with "/mnt"
  public static final String MOUNT_PATH = "/mnt/msg";

  public InfrastructureStack(final Construct parent, final String id) {
    this(parent, id, null);
  }

  public InfrastructureStack(final Construct parent, final String id, final StackProps props) {
    super(parent, id, props);

    // EFS needs to be setup in a VPC
    Vpc vpc = Vpc.Builder.create(this, "Vpc")
        .maxAzs(2)
        .build();

    // Create a file system in EFS to store information
    FileSystem fileSystem = FileSystem.Builder.create(this, "FileSystem")
        .vpc(vpc)
        .removalPolicy(RemovalPolicy.DESTROY)
        .build();

    // Create a access point to EFS
    AccessPoint accessPoint = fileSystem.addAccessPoint("AccessPoint",
        AccessPointOptions.builder()
            .createAcl(
                Acl
                    .builder()
                    .ownerGid("1001").ownerUid("1001").permissions("750")
                    .build())
            .path("/export/lambda")
            .posixUser(
                PosixUser
                    .builder()
                    .gid("1001").uid("1001")
                    .build())
            .build());

    var blacklistFnCommand = List.of(
        "/bin/sh",
        "-c",
        "cd Blacklist && mvn clean install && cp /asset-input/Blacklist/target/blacklist_final.jar /asset-output/"
    );

    var builderOptions = BundlingOptions.builder()
        .image(Runtime.JAVA_11.getBundlingImage())
        .volumes(singletonList(
            // Mount local .m2 repo to avoid download all the dependencies again inside the container
            DockerVolume.builder()
                .hostPath(System.getProperty("user.home") + "/.m2/")
                .containerPath("/root/.m2/")
                .build()
        ))
        .user("root")
        .outputType(ARCHIVED);

    var blacklistFn = new Function(this, "Blacklist", FunctionProps.builder()
        .runtime(Runtime.JAVA_11)
        .code(Code.fromAsset("../software/", AssetOptions.builder()
            .bundling(builderOptions
                .command(blacklistFnCommand)
                .build())
            .build()))
        .handler("com.devilelephant.blacklist.BlacklistFn")
        .memorySize(512)
        .timeout(Duration.seconds(30))
        .vpc(vpc)
        .filesystem(fromEfsAccessPoint(accessPoint, MOUNT_PATH))
        .logRetention(RetentionDays.ONE_WEEK)
        .build());

    var httpApi = new HttpApi(this, "sample-api", HttpApiProps.builder()
        .apiName("sample-api")
        .build());

    httpApi.addRoutes(AddRoutesOptions.builder()
        .path("/blacklist")
        .methods(singletonList(HttpMethod.GET))
        .integration(new LambdaProxyIntegration(LambdaProxyIntegrationProps.builder()
            .handler(blacklistFn)
            .payloadFormatVersion(PayloadFormatVersion.VERSION_2_0)
            .build()))
        .build());

    var fireholUpdaterFnCommand = List.of(
        "/bin/sh",
        "-c",
        "cd FireholUpdater && mvn clean install && cp /asset-input/FireholUpdater/target/firehol_final.jar /asset-output/"
    );
    var fireholUpdaterFn = new Function(this, "FireholUpdater", FunctionProps.builder()
        .runtime(Runtime.JAVA_11)
        .code(Code.fromAsset("../software/", AssetOptions.builder()
            .bundling(builderOptions
                .command(fireholUpdaterFnCommand)
                .build())
            .build()))
        .handler("com.devilelephant.fireholupdater.FireholUpdaterFn")
        .memorySize(2048)
        .timeout(Duration.minutes(5))
        .vpc(vpc)
        .filesystem(software.amazon.awscdk.services.lambda.FileSystem.fromEfsAccessPoint(accessPoint, MOUNT_PATH))
        .logRetention(RetentionDays.ONE_WEEK)
        .build());

    new CfnOutput(this, "HttApi", CfnOutputProps.builder()
        .description("Url for Http Api")
        .value(httpApi.getApiEndpoint())
        .build());
  }
}