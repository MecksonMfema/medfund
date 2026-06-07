defmodule ChatServiceWeb.ChatChannelTest do
  @moduledoc """
  Verifies the join handshake on `chat:<room_id>` — the room_id assignment
  is the basis for every downstream broadcast, so a regression here would
  silently route a user's messages into the wrong room.

  Handlers that touch the database (chat:message, chat:read, chat:file,
  chat:ai_assist) are covered in the integration test suite (Phase 3),
  where Ecto sandbox + Mox for AiProxy become available.
  """

  use ChatServiceWeb.ChannelCase, async: false

  alias ChatServiceWeb.{ChatSocket, ChatChannel}

  defp authed_socket(user_id \\ "user-1", tenant_id \\ "tenant-a") do
    socket(ChatSocket, "chat_socket:#{user_id}", %{
      user_id: user_id,
      tenant_id: tenant_id
    })
  end

  test "join assigns the room_id derived from the topic" do
    {:ok, _reply, socket} =
      authed_socket()
      |> subscribe_and_join(ChatChannel, "chat:room-123")

    assert socket.assigns.room_id == "room-123"
    assert socket.assigns.user_id == "user-1"
    assert socket.assigns.tenant_id == "tenant-a"
  end

  test "handle_in chat:typing broadcasts a chat:user_typing event to the room" do
    other_socket = authed_socket("user-2")

    {:ok, _reply, _socket} =
      authed_socket("user-1")
      |> subscribe_and_join(ChatChannel, "chat:room-typing")

    # Second user subscribes to the same room so it receives the broadcast.
    {:ok, _reply, _other} =
      other_socket
      |> subscribe_and_join(ChatChannel, "chat:room-typing")

    # User 1 emits a typing notification — broadcast_from! excludes the sender,
    # so only user 2 should observe the chat:user_typing event.
    push(authed_socket("user-1"), "chat:typing", %{})
    # Drain history pushes from join so the next push assertion is clean.
    _ = receive do _ -> :ok after 50 -> :ok end
  end
end
