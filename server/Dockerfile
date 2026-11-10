FROM golang:1.22-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /out/unscroll-sync-server ./cmd/unscroll-sync-server

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/unscroll-sync-server /unscroll-sync-server
ENV ADDR=0.0.0.0:8781
ENV DATA=/data
EXPOSE 8781
VOLUME ["/data"]
ENTRYPOINT ["/unscroll-sync-server"]
