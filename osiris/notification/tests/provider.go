package main

import (
	"context"
	"fmt"
	"net"
	"net/http/httptest"
	"testing"
	"time"

	pb "github.com/bptlab/cepta/models/grpc/notification"
	usermgmtpb "github.com/bptlab/cepta/models/grpc/usermgmt"
	"github.com/bptlab/cepta/models/internal/types/users"
	libcli "github.com/bptlab/cepta/osiris/lib/cli"
	libdb "github.com/bptlab/cepta/osiris/lib/db"
	"github.com/bptlab/cepta/osiris/lib/kafka"
	kafkaconsumer "github.com/bptlab/cepta/osiris/lib/kafka/consumer"
	kafkaproducer "github.com/bptlab/cepta/osiris/lib/kafka/producer"
	rmq "github.com/bptlab/cepta/osiris/lib/rabbitmq"
	rmqc "github.com/bptlab/cepta/osiris/lib/rabbitmq/consumer"
	rmqp "github.com/bptlab/cepta/osiris/lib/rabbitmq/producer"
	usermgmt "github.com/bptlab/cepta/osiris/usermgmt"
	"github.com/gorilla/websocket"
	tc "github.com/romnnn/testcontainers"
	tcmongo "github.com/romnnn/testcontainers/mongo"
	tckafka "github.com/romnnn/testcontainers/kafka"
	tcrabbitmq "github.com/romnnn/testcontainers/rabbitmq"
	log "github.com/sirupsen/logrus"
	"github.com/testcontainers/testcontainers-go"
	"google.golang.org/grpc"
	"google.golang.org/grpc/test/bufconn"
)

const (
	logLevel       = log.DebugLevel
	bufSize        = 1024 * 1024
	userCollection = "mock-users"
)

type dialerFunc = func(string, time.Duration) (net.Conn, error)

func dialerFor(listener *bufconn.Listener) dialerFunc {
	return func(string, time.Duration) (net.Conn, error) {
		return listener.Dial()
	}
}

// Test ...
type Test struct {
	Net    testcontainers.Network
	MongoC testcontainers.Container
	KafkaC testcontainers.Container
	ZkC    testcontainers.Container
	RmqC   testcontainers.Container

	kafkacConfig kafkaconsumer.Config
	kafkapConfig kafkaproducer.Config
	MongoConfig  libdb.MongoDBConfig
	rmqcConfig   rmqc.Config
	rmqpConfig   rmqp.Config

	notificationEndpoint *grpc.ClientConn
	notificationServer   *NotificationServer
	notificationClient   pb.NotificationClient

	usermgmtEndpoint *grpc.ClientConn
	usermgmtServer   *usermgmt.UserMgmtServer
	usermgmtClient   usermgmtpb.UserManagementClient

	websocketListener net.Listener

	// TODO: Remove
	websocketConnection *websocket.Conn
	websocketClient     *websocket.Conn
	websocketServer     *httptest.Server
}

func setUpUserMgmtServer(t *testing.T, listener *bufconn.Listener, mongoConfig libdb.MongoDBConfig) (*usermgmt.UserMgmtServer, error) {
	server := usermgmt.NewUserMgmtServer(mongoConfig)
	server.UserCollection = userCollection
	server.DefaultUser = users.InternalUser{
		User: &users.User{
			Email: "default-user@web.de",
		},
		Password: "admins-have-the-best-passwords",
	}
	if err := server.Setup(); err != nil {
		t.Fatalf("Failed to setup user management server: %v", err)
	}
	go func() {
		server.Serve(listener)
	}()
	return &server, nil
}

func setUpNotificationServer(t *testing.T, grpcListener *bufconn.Listener, wsListener net.Listener, kafkacConfig kafkaconsumer.Config, usermgmtEndpoint *grpc.ClientConn, rmqpConfig rmqp.Config) (*NotificationServer, error) {
	server := NewNotificationServer(kafkacConfig, rmqpConfig)
	if err := server.Setup(context.Background(), usermgmtEndpoint); err != nil {
		t.Fatalf("Failed to setup replayer server: %v", err)
	}
	go func() {
		if err := server.Serve(grpcListener, wsListener); err != nil {
			t.Fatalf("Failed to serve the replayer: %v", err)
		}
	}()
	return &server, nil
}

func teardownServer(server interface{ Shutdown() }) {
	server.Shutdown()
}

func (test *Test) setup(t *testing.T) *Test {
	var err error
	log.SetLevel(logLevel)
	if parallel {
		t.Parallel()
	}

	networkName := fmt.Sprintf("test-network-%s", tc.UniqueID())
	test.Net, err = tc.CreateNetwork(testcontainers.NetworkRequest{
		Driver:         "bridge",
		Name:           networkName,
		Attachable:     true,
		CheckDuplicate: true,
	}, 5)
	if err != nil {
		t.Fatalf("Failed to create the docker test network: %v", err)
		return test
	}
	defer test.Net.Remove(context.Background())

	containerOptions := tc.ContainerOptions{
		ContainerRequest: testcontainers.ContainerRequest{
			Networks: []string{networkName},
		},
	}

	// Start mongodb container
	var mongoConfig tcmongo.DBConfig
	test.MongoC, mongoConfig, err = tcmongo.StartMongoContainer(tcmongo.ContainerOptions{ContainerOptions: containerOptions})
	if err != nil {
		t.Fatalf("Failed to start the mongodb container: %v", err)
		return test
	}
	test.MongoConfig = libdb.MongoDBConfig{
		Host:                mongoConfig.Host,
		Port:                mongoConfig.Port,
		User:                mongoConfig.User,
		Database:            fmt.Sprintf("mockdatabase-%s", tc.UniqueID()),
		Password:            mongoConfig.Password,
		ConnectionTolerance: libcli.ConnectionTolerance{TimeoutSec: 20},
	}

	// Start kafka container
	var kafkaConfig *tckafka.ContainerConnectionConfig
	test.KafkaC, kafkaConfig, test.ZkC, _, err = tckafka.StartKafkaContainer(tckafka.ContainerOptions{ContainerOptions: containerOptions})
	if err != nil {
		t.Fatalf("Failed to start the kafka container: %v", err)
		return test
	}
	baseKafkaConfig := kafka.Config{
		Brokers:             kafkaConfig.Brokers,
		Version:             kafkaConfig.KafkaVersion,
		ConnectionTolerance: libcli.ConnectionTolerance{TimeoutSec: 20},
	}
	test.kafkacConfig = kafkaconsumer.Config{
		Config: baseKafkaConfig,
		Group:  fmt.Sprintf("TestConsumerGroup-%s", tc.UniqueID()),
	}
	test.kafkapConfig = kafkaproducer.Config{
		Config: baseKafkaConfig,
	}
	log.Error(test.kafkacConfig)
	log.Error(test.kafkapConfig)

	// Start rabbitmq container
	var rmqConConfig tcrabbitmq.Config
	test.RmqC, rmqConConfig, err = tcrabbitmq.StartRabbitmqContainer(tcrabbitmq.ContainerOptions{ContainerOptions: containerOptions})
	if err != nil {
		t.Fatalf("Failed to start the rabbitmq container: %v", err)
		return test
	}
	rmqConfig := rmq.Config{
		Host:         rmqConConfig.Host,
		Port:         rmqConConfig.Port,
		ExchangeName: "TestExchangeName",
	}
	test.rmqcConfig = rmqc.Config{
		Config: rmqConfig,
	}
	test.rmqpConfig = rmqp.Config{
		Config: rmqConfig,
	}

	var grpcListener = bufconn.Listen(bufSize)
	var usermgmtListener = bufconn.Listen(bufSize)
	// Choose a random free port for the websocket listener
	test.websocketListener, err = net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("Failed to create listener for the websocket connection")
	}

	// User management service
	test.usermgmtEndpoint, err = grpc.DialContext(context.Background(), "bufnet", grpc.WithContextDialer(func(context.Context, string) (net.Conn, error) {
		return usermgmtListener.Dial()
	}), grpc.WithInsecure())
	if err != nil {
		t.Fatalf("Failed to dial bufnet: %v", err)
		return test
	}

	test.usermgmtServer, err = setUpUserMgmtServer(t, usermgmtListener, test.MongoConfig)
	if err != nil {
		t.Fatalf("Failed to setup the user management service: %v", err)
		return test
	}

	test.usermgmtClient = usermgmtpb.NewUserManagementClient(test.usermgmtEndpoint)

	// Notification service
	test.notificationEndpoint, err = grpc.DialContext(context.Background(), "bufnet", grpc.WithContextDialer(func(context.Context, string) (net.Conn, error) {
		return grpcListener.Dial()
	}), grpc.WithInsecure())
	if err != nil {
		t.Fatalf("Failed to dial bufnet: %v", err)
		return test
	}
	test.notificationServer, err = setUpNotificationServer(t, grpcListener, test.websocketListener, test.kafkacConfig, test.usermgmtEndpoint, test.rmqpConfig)
	if err != nil {
		t.Fatalf("Failed to setup the replayer service: %v", err)
		return test
	}
	test.notificationClient = pb.NewNotificationClient(test.notificationEndpoint)

	return test
}

func (test *Test) teardown() {
	test.notificationServer.Shutdown()
	test.notificationEndpoint.Close()
	test.usermgmtServer.Shutdown()
	test.usermgmtEndpoint.Close()
	test.MongoC.Terminate(context.Background())
	test.KafkaC.Terminate(context.Background())
	test.ZkC.Terminate(context.Background())
	test.Net.Remove(context.Background())
	// test.websocketConnection.Close()
	// test.websocketClient.Close()
	// test.websocketServer.Close()
}
