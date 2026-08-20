FROM golang:1.23-alpine AS build

WORKDIR /src
COPY go.mod ./
COPY *.go ./
COPY openapi.yaml ./
COPY static ./static
RUN go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /deepclient-manager .

FROM gcr.io/distroless/static-debian12
COPY --from=build /deepclient-manager /deepclient-manager
EXPOSE 8080
ENTRYPOINT ["/deepclient-manager"]
