package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"

	pb "github.com/bptlab/cepta/models/grpc/user_management"
	libcli "github.com/bptlab/cepta/osiris/lib/cli"
	libdb "github.com/bptlab/cepta/osiris/lib/db"

	"github.com/jinzhu/gorm"
	"github.com/lib/pq"
	"github.com/sirupsen/logrus"
	"github.com/urfave/cli/v2"
	"google.golang.org/grpc"
)

var grpcServer *grpc.Server
var done = make(chan bool, 1)
var db *libdb.DB
var log *logrus.Logger

type server struct {
	pb.UnimplementedUserManagementServer
	active bool
}

//Account is a struct to rep user account
type Account struct {
	gorm.Model
	Email    string         `json:"email"`
	TrainIds pq.StringArray `gorm:"type:int[]"`
	Password string         `json:"password"`
	Token    string         `json:"token";sql:"-"`
}

/*
rpc SetEmail(UserId, string) returns (Success) {}
rpc AddTrain(UserId, Train) returns (Success) {}
rpc RemoveTrain(UserId, Train) returns (Success) {}
rpc AddUser(User) returns (Success) {}
rpc RemoveUser(UserId) returns (Success) {}

rpc GetUser(UserId) returns (User) {}
rpc GetTrains(UserId) returns (Trains) {}
*/

// SetEmail sets a users email
func (server *server) SetEmail(ctx context.Context, in *pb.UserIdEmailInput) (*pb.Success, error) {
	var id int = int(in.GetUserId().GetUserId())
	var email string = in.GetEmail()
	var account Account
	db.DB.First(&account, "id = ?", id)
	db.DB.Model(&account).Update("Email", email)
	return &pb.Success{Success: true}, nil
}

func main() {

	shutdown := make(chan os.Signal)
	signal.Notify(shutdown, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-shutdown
		log.Info("Graceful shutdown")
		log.Info("Stopping GRPC server")
		grpcServer.Stop()
	}()

	cliFlags := []cli.Flag{}
	cliFlags = append(cliFlags, libcli.CommonCliOptions(libcli.ServicePort, libcli.ServiceLogLevel)...)
	cliFlags = append(cliFlags, libdb.DatabaseCliOptions...)

	log = logrus.New()
	go func() {
		app := &cli.App{
			Name:  "CEPTA User management server",
			Usage: "manages the user database",
			Flags: cliFlags,
			Action: func(ctx *cli.Context) error {
				level, err := logrus.ParseLevel(ctx.String("log"))
				if err != nil {
					log.Warnf("Log level '%s' does not exist.")
					level = logrus.InfoLevel
				}
				log.SetLevel(level)
				ret := serve(ctx, log)
				return ret
			},
		}
		err := app.Run(os.Args)
		if err != nil {
			log.Fatal(err)
		}
	}()

	<-done
	log.Info("Exiting")
}

func serve(ctx *cli.Context, log *logrus.Logger) error {
	postgresConfig := libdb.DBConfig{}.ParseCli(ctx)

	var err error
	db, err = libdb.PostgresDatabase(&postgresConfig)
	db.DB.Debug().AutoMigrate(&Account{})

	if err != nil {
		log.Fatalf("failed to initialize database: %v", err)
	}

	userManagementServer := server{
		active: true,
	}

	port := fmt.Sprintf(":%d", ctx.Int("port"))
	log.Printf("Serving at %s", port)
	listener, err := net.Listen("tcp", port)
	if err != nil {
		log.Fatalf("failed to listen: %v", err)
	}
	grpcServer = grpc.NewServer()
	pb.RegisterUserManagementServer(grpcServer, &userManagementServer)

	if err := grpcServer.Serve(listener); err != nil {
		log.Fatalf("failed to serve: %v", err)
	}
	log.Info("Closing socket")
	listener.Close()
	done <- true
	return nil
}
