defmodule LiveDashboardWeb.DashboardChannelTest do
  @moduledoc """
  Verifies the join-time tenant-isolation rule on `dashboard:<tenant_id>` —
  the regression target. A user authenticated for tenant A must not be able to
  subscribe to tenant B's PubSub stream.
  """

  use LiveDashboardWeb.ChannelCase, async: false

  alias LiveDashboardWeb.{DashboardSocket, DashboardChannel}

  defp socket_for(tenant_id, user_id \\ "user-1") do
    socket(DashboardSocket, "user_socket:#{user_id}", %{
      user_id: user_id,
      tenant_id: tenant_id,
      roles: ["operator"]
    })
  end

  test "join with matching tenant_id is accepted" do
    {:ok, _reply, socket} =
      socket_for("tenant-a")
      |> subscribe_and_join(DashboardChannel, "dashboard:tenant-a")

    assert socket.assigns.tenant_id == "tenant-a"
  end

  test "join is rejected when the requested tenant does not match the socket assign" do
    assert {:error, %{reason: "unauthorized"}} =
             socket_for("tenant-a")
             |> subscribe_and_join(DashboardChannel, "dashboard:tenant-b")
  end

  test "lobby is a shared topic everyone can join regardless of tenant" do
    assert {:ok, _reply, _socket} =
             socket_for("tenant-a")
             |> subscribe_and_join(DashboardChannel, "dashboard:lobby")
  end

  test "after_join pushes a snapshot containing a timestamp" do
    {:ok, _reply, _socket} =
      socket_for("tenant-a")
      |> subscribe_and_join(DashboardChannel, "dashboard:tenant-a")

    assert_push("dashboard:snapshot", %{timestamp: ts})
    assert is_binary(ts)
  end

  test "handle_in request_update pushes a fresh stats snapshot" do
    {:ok, _reply, socket} =
      socket_for("tenant-a")
      |> subscribe_and_join(DashboardChannel, "dashboard:tenant-a")

    # Drain the after_join snapshot first so the next push is observable.
    assert_push("dashboard:snapshot", %{})

    push(socket, "request_update", %{})
    assert_push("dashboard:update", %{timestamp: ts})
    assert is_binary(ts)
  end

  test "PubSub broadcast pushes a dashboard:event message to subscribers" do
    {:ok, _reply, _socket} =
      socket_for("tenant-a")
      |> subscribe_and_join(DashboardChannel, "dashboard:tenant-a")

    assert_push("dashboard:snapshot", %{})

    Phoenix.PubSub.broadcast(
      LiveDashboard.PubSub,
      "events:tenant-a",
      {:event, %{type: "CLAIM_SUBMITTED", tenant_id: "tenant-a"}}
    )

    assert_push("dashboard:event", %{type: "CLAIM_SUBMITTED"})
    assert_push("dashboard:update", %{timestamp: _})
  end

  test "PubSub broadcast on a different tenant's topic does not leak to this channel" do
    {:ok, _reply, _socket} =
      socket_for("tenant-a")
      |> subscribe_and_join(DashboardChannel, "dashboard:tenant-a")

    assert_push("dashboard:snapshot", %{})

    Phoenix.PubSub.broadcast(
      LiveDashboard.PubSub,
      "events:tenant-b",
      {:event, %{type: "CLAIM_SUBMITTED", tenant_id: "tenant-b"}}
    )

    refute_push("dashboard:event", %{}, 50)
  end
end
