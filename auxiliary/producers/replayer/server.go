package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/bptlab/cepta/ci/versioning"
	pb "github.com/bptlab/cepta/models/grpc/replayer"
	libcli "github.com/bptlab/cepta/osiris/lib/cli"
	libdb "github.com/bptlab/cepta/osiris/lib/db"
	kafkaproducer "github.com/bptlab/cepta/osiris/lib/kafka/producer"
	"github.com/bptlab/cepta/osiris/lib/utils"
	clivalues "github.com/romnnn/flags4urfavecli/values"

	topics "github.com/bptlab/cepta/models/constants/topic"

	tspb "github.com/golang/protobuf/ptypes/timestamp"
	"github.com/sirupsen/logrus"
	"github.com/urfave/cli/v2"
	"google.golang.org/grpc"
	"google.golang.org/grpc/metadata"
)

// Version will be injected at build time
var Version string = "Unknown"

// BuildTime will be injected at build time
var BuildTime string = ""

var server ReplayerServer
var grpcServer *grpc.Server
var done = make(chan bool, 1)
var log *logrus.Logger

// ReplayerServer ...
type ReplayerServer struct {
	pb.UnimplementedReplayerServer

	StartOptions pb.ReplayStartOptions

	// Speed int32
	// Limit       int
	// Mode pb.ReplayType
	// Timerange   pb.Timerange
	// Ids         []string
	// Included    []string
	// Excluded    []string
	Active bool
	// Repeat      bool
	KafkaConfig kafkaproducer.KafkaProducerOptions
	MongoConfig libdb.MongoDBConfig

	Replayers []*Replayer

	CheckpointsRplr           *Replayer
	CrewActivityRplr          *Replayer
	CrewEndRplr               *Replayer
	CrewShiftRplr             *Replayer
	CrewTransitionRplr        *Replayer
	DelayExplanationRplr      *Replayer
	InfrastructureManagerRplr *Replayer
	LiveTrainRplr             *Replayer
	LocationRplr              *Replayer
	PlannedTrainRplr          *Replayer
	PredictedTrainRplr        *Replayer
	RailwayUndertakingRplr    *Replayer
	StationRplr               *Replayer
	TrainInformationRplr      *Replayer
	VehicleRplr               *Replayer
	WeatherRplr               *Replayer
	GpsRplr                   *Replayer

	mongo    *libdb.MongoDB
	producer *kafkaproducer.KafkaProducer
}

// NewReplayerServer ...
func NewReplayerServer(mongoConfig libdb.MongoDBConfig, kafkaConfig kafkaproducer.KafkaProducerOptions) ReplayerServer {
	srv := ReplayerServer{
		KafkaConfig: kafkaConfig,
		MongoConfig: mongoConfig,
	}
	srv.Setup()
	return srv
}

// SeekTo ...
func (s *ReplayerServer) SeekTo(ctx context.Context, in *tspb.Timestamp) (*pb.Success, error) {
	log.Infof("Seeking to: %v", in)

	// Overrides all timerange starting points
	s.StartOptions.Options.Timerange.Start = in
	for _, source := range s.StartOptions.Sources {
		source.Options.Timerange.Start = in
	}
	for _, replayer := range s.Replayers {
		// Send RESET control message
		// replayer.Options
		replayer.Ctrl <- pb.InternalControlMessageType_RESET
	}
	return &pb.Success{Success: true}, nil
}

// Reset ...
func (s *ReplayerServer) Reset(ctx context.Context, in *pb.Empty) (*pb.Success, error) {
	log.Infof("Resetting")
	for _, replayer := range s.Replayers {
		// Send RESET control message
		replayer.Ctrl <- pb.InternalControlMessageType_RESET
	}
	return &pb.Success{Success: true}, nil
}

// Start ...
func (s *ReplayerServer) Start(ctx context.Context, in *pb.ReplayStartOptions) (*pb.Success, error) {
	log.Infof("Starting")
	s.Active = true
	s.StartOptions = *in
	// Include replayers
	if len(s.StartOptions.Sources) < 1 {
		for _, replayer := range s.Replayers {
			s.include(replayer)
		}
	}
	for _, source := range s.StartOptions.Sources {
		if replayer, included := getReplayer(s.Replayers, source.Source); included {
			// Send START control message
			replayer.Options = source
			replayer.Ctrl <- pb.InternalControlMessageType_START
		}
	}
	return &pb.Success{Success: true}, nil
}

// Stop ...
func (s *ReplayerServer) Stop(ctx context.Context, in *pb.Empty) (*pb.Success, error) {
	log.Infof("Stopping")
	if md, ok := metadata.FromIncomingContext(ctx); ok {
		log.Info(md)
	}
	s.Active = false
	for _, replayer := range s.Replayers {
		// Send STOP control message
		replayer.Ctrl <- pb.InternalControlMessageType_STOP
	}
	return &pb.Success{Success: true}, nil
}

// SetSpeed ...
func (s *ReplayerServer) SetSpeed(ctx context.Context, in *pb.Speed) (*pb.Success, error) {
	log.Infof("Setting speed to: %v", int(in.GetSpeed()))
	// speed = int32(in.GetSpeed())
	// Overrides all speed values
	s.StartOptions.Options.Speed = in
	for _, source := range s.StartOptions.Sources {
		source.Options.Speed = in
	}
	return &pb.Success{Success: true}, nil
}

// SetType ...
func (s *ReplayerServer) SetType(ctx context.Context, in *pb.ReplayModeOption) (*pb.Success, error) {
	log.Infof("Setting replay type to: %v", in.GetMode())
	// Overrides all modes
	s.StartOptions.Options.Mode = in.GetMode()
	for _, source := range s.StartOptions.Sources {
		source.Options.Mode = in.GetMode()
	}
	return &pb.Success{Success: true}, nil
}

// SetOptions ...
func (s *ReplayerServer) SetOptions(ctx context.Context, in *pb.ReplaySetOptionsRequest) (*pb.Success, error) {
	log.Infof("Setting replay options")
	/*
		success, err := s.SetSpeed(ctx, in.GetSpeed())
		if err != nil {
			return success, err
		}
		success, err = s.SeekTo(ctx, in.GetTimerange().GetStart())
		if err != nil {
			return success, err
		}
		success, err = s.SetType(ctx, &pb.ReplayModeOption{Mode: in.GetMode()})
		if err != nil {
			return success, err
		}
	*/
	return &pb.Success{Success: true}, nil
}

// GetStatus ...
func (s *ReplayerServer) GetStatus(ctx context.Context, in *pb.Empty) (*pb.ReplayStatus, error) {
	log.Info("Handling query for current replay status")
	return &pb.ReplayStatus{Active: s.Active}, nil
}

// GetOptions ...
func (s *ReplayerServer) GetOptions(ctx context.Context, in *pb.Empty) (*pb.ReplayStartOptions, error) {
	log.Info("Handling query for current replay options")
	return &s.StartOptions, nil
}

// Query ...
func (s *ReplayerServer) Query(in *pb.QueryOptions, stream pb.Replayer_QueryServer) error {
	log.Infof("Handling query for %d sources", len(in.Sources))
	for _, source := range in.Sources {
		if replayer, included := getReplayer(s.Replayers, source.Source); included {
			// Collect all replay datasets from all replayers
			replayer.Options = source
			if err := replayer.queryAndSend(stream); err != nil {
				return err
			}
		}
	}
	return nil
}

func getReplayer(replayers []*Replayer, included topics.Topic) (*Replayer, bool) {
	for _, replayer := range replayers {
		if replayer.Topic == included {
			return replayer, true
		}
	}
	return nil, false
}

func filterReplayers(replayers []*Replayer, filterFunc func(r *Replayer) bool) []*Replayer {
	var included []*Replayer
	for _, replayer := range replayers {
		if filterFunc(replayer) {
			included = append(included, replayer)
		}
	}
	return included
}

func (s *ReplayerServer) include(r *Replayer) {
	for _, source := range s.StartOptions.Sources {
		if source.Source == r.Topic {
			return
		}
	}
	s.StartOptions.Sources = append(s.StartOptions.Sources, &pb.SourceQueryOptions{
		Source: r.Topic,
	})
}

// Serve ...
func (s *ReplayerServer) Serve(listener net.Listener, log *logrus.Logger, includedSrcs []string, excludedSrcs []string) error {

	// For reference: When using postgres as a replaying database:
	/*
		postgresConfig := libdb.PostgresDBConfig{}.ParseCli(ctx)
		postgres, err := libdb.PostgresDatabase(&postgresConfig)
		if err != nil {
			log.Fatalf("failed to initialize postgres database: %v", err)
		}
	*/
	// Connect to mongoDB
	mongo, err := libdb.MongoDatabase(&s.MongoConfig)
	if err != nil {
		log.Fatalf("Failed to initialize mongo database: %v", err)
	}
	*s.mongo = *mongo

	// Connect to kafka
	s.producer, err = kafkaproducer.KafkaProducer{}.Create(s.KafkaConfig)
	if err != nil {
		log.Fatalf("Cannot produce events: %s", err.Error())
	}
	defer func() {
		if err := s.producer.Close(); err != nil {
			log.Errorf("Failed to close kafka producer: %v", err)
		}
		if err := s.mongo.Close(); err != nil {
			log.Errorf("Failed to close mongo connection: %v", err)
		}
	}()

	for _, replayer := range s.Replayers {
		// Set common replayer parameters
		replayer.producer = s.producer
		replayer.Ctrl = make(chan pb.InternalControlMessageType)
		replayer.Brokers = s.KafkaConfig.Brokers
		go replayer.Start(log)
	}

	log.Infof("Serving at %s", listener.Addr())
	log.Info("Replayer ready")
	grpcServer = grpc.NewServer()
	pb.RegisterReplayerServer(grpcServer, s)

	if err := grpcServer.Serve(listener); err != nil {
		log.Fatalf("failed to serve: %v", err)
	}
	log.Info("Closing socket")
	listener.Close()
	done <- true
	return nil
}

// Shutdown ...
func (s *ReplayerServer) Shutdown() {
	log.Info("Graceful shutdown")
	log.Info("Sending SHUTDOWN signal to all replaying topics")
	for _, replayer := range s.Replayers {
		log.Debugf("Sending SHUTDOWN signal to %s", replayer.SourceName)
		replayer.Ctrl <- pb.InternalControlMessageType_SHUTDOWN
		// Wait for ack
		log.Debugf("Waiting for ack from %s", replayer.SourceName)
		<-replayer.Ctrl
		log.Debugf("Shutdown complete for %s", replayer.SourceName)
	}

	log.Info("Stopping GRPC server")
	grpcServer.Stop()
}

func main() {
	// Register shutdown routine
	shutdown := make(chan os.Signal)
	signal.Notify(shutdown, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-shutdown
		server.Shutdown()
	}()

	var sources []string
	for t := range topics.Topic_value {
		sources = append(sources, t)
	}

	cliFlags := []cli.Flag{}
	cliFlags = append(cliFlags, libcli.CommonCliOptions(libcli.ServicePort, libcli.ServiceLogLevel)...)
	cliFlags = append(cliFlags, libcli.CommonCliOptions(libcli.ServiceConnectionTolerance)...)
	cliFlags = append(cliFlags, libdb.PostgresDatabaseCliOptions...)
	cliFlags = append(cliFlags, libdb.MongoDatabaseCliOptions...)
	cliFlags = append(cliFlags, kafkaproducer.KafkaProducerCliOptions...)
	cliFlags = append(cliFlags, []cli.Flag{
		&cli.GenericFlag{
			Name: "include-sources",
			Value: &clivalues.EnumListValue{
				Enum:       sources,
				Default:    []string{},
				AllowEmpty: true,
			},
			EnvVars: []string{"INCLUDE_SOURCES"},
			Usage:   "sources to be included in the replay (default: all)",
		},
		&cli.GenericFlag{
			Name: "exclude-sources",
			Value: &clivalues.EnumListValue{
				Enum:       sources,
				Default:    []string{},
				AllowEmpty: true,
			},
			EnvVars: []string{"EXCLUDE_SOURCES"},
			Usage:   "sources to be excluded from the replay (default: none)",
		},
		&cli.GenericFlag{
			Name: "mode",
			Value: &clivalues.EnumValue{
				Enum:    []string{"constant", "proportional"},
				Default: "proportional",
			},
			Aliases: []string{"replay-type", "type", "replay"},
			EnvVars: []string{"REPLAY_MODE", "MODE", "REPLAY"},
			Usage:   "replay mode (constant or proportional)",
		},
		&cli.IntFlag{
			Name:    "frequency",
			Value:   5000,
			Aliases: []string{"freq", "speed"},
			EnvVars: []string{"FREQUENCY", "SPEED", "FREQ"},
			Usage:   "speedup factor for proportional replay (as integer)",
		},
		&cli.IntFlag{
			Name:    "pause",
			Value:   2000,
			Aliases: []string{"wait"},
			EnvVars: []string{"PAUSE"},
			Usage:   "pause between sending events when using constant replay (in milliseconds)",
		},
		&cli.BoolFlag{
			Name:    "repeat",
			Value:   true,
			EnvVars: []string{"REPEAT"},
			Usage:   "whether or not to automatically resume and repeat the replay",
		},
		&cli.GenericFlag{
			Name:    "start-timestamp",
			Value:   &clivalues.TimestampValue{},
			Aliases: []string{"start"},
			EnvVars: []string{"START_TIMESTAMP", "START"},
			Usage:   "start timestamp",
		},
		&cli.GenericFlag{
			Name:    "end-timestamp",
			Value:   &clivalues.TimestampValue{},
			Aliases: []string{"end"},
			EnvVars: []string{"END_TIMESTAMP", "END"},
			Usage:   "end timestamp",
		},
	}...)

	log = logrus.New()

	app := &cli.App{
		Name:    "CEPTA Train data replayer producer",
		Version: versioning.BinaryVersion(Version, BuildTime),
		Usage:   "Produces train data events by replaying a database dump",
		Flags:   cliFlags,
		Action: func(ctx *cli.Context) error {
			go func() {
				level, err := logrus.ParseLevel(ctx.String("log"))
				if err != nil {
					log.Warnf("Log level '%s' does not exist.")
					level = logrus.InfoLevel
				}
				log.SetLevel(level)
				port := fmt.Sprintf(":%d", ctx.Int("port"))
				listener, err := net.Listen("tcp", port)
				if err != nil {
					log.Fatalf("failed to listen: %v", err)
				}

				server = NewReplayerServer(
					libdb.MongoDBConfig{}.ParseCli(ctx),
					kafkaproducer.KafkaProducerOptions{}.ParseCli(ctx),
				)
				server.StartOptions.Options = &pb.ReplayOptions{Repeat: ctx.Bool("repeat")}

				// Parse included and excluded values
				included := clivalues.EnumListValue{}.Parse(ctx.String("include-sources"))
				log.Infof("Include: %s", included)
				excluded := clivalues.EnumListValue{}.Parse(ctx.String("exclude-sources"))
				log.Infof("Exclude: %s", excluded)

				replayers := filterReplayers(server.Replayers, func(r *Replayer) bool {
					if len(included) > 0 && !utils.Contains(included, r.Topic.String()) {
						return false
					}
					if len(excluded) > 0 && utils.Contains(excluded, r.Topic.String()) {
						return false
					}
					return true
				})
				for _, r := range replayers {
					server.include(r)
				}

				// Parse replay mode
				if mode, found := pb.ReplayMode_value[strings.ToUpper(ctx.String("mode"))]; found {
					server.StartOptions.Options.Mode = pb.ReplayMode(mode)
				} else {
					server.StartOptions.Options.Mode = pb.ReplayMode_PROPORTIONAL
				}

				// Parse timerange
				if startTime, err := time.Parse(clivalues.DefaultTimestampFormat, ctx.String("start-timestamp")); err != nil {
					if protoStartTime, err := utils.ToProtoTime(startTime); err != nil {
						server.StartOptions.Options.Timerange.Start = protoStartTime
					}
				}
				if endTime, err := time.Parse(clivalues.DefaultTimestampFormat, ctx.String("end-timestamp")); err != nil {
					if protoEndTime, err := utils.ToProtoTime(endTime); err != nil {
						server.StartOptions.Options.Timerange.End = protoEndTime
					}
				}

				// Parse pause / frequency
				switch server.StartOptions.Options.Mode {
				case pb.ReplayMode_CONSTANT:
					server.StartOptions.Options.Speed = &pb.Speed{Speed: int32(ctx.Int("pause"))}
					log.Infof("Using constant replay with pause=%d", server.StartOptions.Options.Speed)
				case pb.ReplayMode_PROPORTIONAL:
					server.StartOptions.Options.Speed = &pb.Speed{Speed: int32(ctx.Int("frequency"))}
					log.Infof("Using proportional replay with frequency=%d", server.StartOptions.Options.Speed)
				default:
					server.StartOptions.Options.Speed = &pb.Speed{Speed: 5000}
				}

				server.Serve(listener, log, included, excluded)
			}()
			<-done
			log.Info("Exiting")
			return nil
		},
	}
	err := app.Run(os.Args)
	if err != nil {
		log.Fatal(err)
	}
}
