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

  # room_id is a :binary_id (UUID) column — the channel loads history on
  # after_join, so non-UUID topic suffixes blow up with Ecto.Query.CastError.
  defp uuid, do: Ecto.UUID.generate()

  test "join assigns the room_id derived from the topic" do
    room_id = uuid()

    {:ok, _reply, socket} =
      authed_socket()
      |> subscribe_and_join(ChatChannel, "chat:" <> room_id)

    assert socket.assigns.room_id == room_id
    assert socket.assigns.user_id == "user-1"
    assert socket.assigns.tenant_id == "tenant-a"
  end

  test "handle_in chat:typing broadcasts a chat:user_typing event to the room" do
    room_id = uuid()

    # User 1 joins. push/3 requires a joined socket — keep the returned one.
    {:ok, _reply, user1_socket} =
      authed_socket("user-1")
      |> subscribe_and_join(ChatChannel, "chat:" <> room_id)

    # Subscribe the test process to the room so it observes the broadcast
    # (instead of relying on a second channel process).
    Phoenix.PubSub.subscribe(ChatService.PubSub, "chat:" <> room_id)

    # broadcast_from! excludes the sender, so user 1's typing notification
    # arrives in the test process (which is acting as the second subscriber).
    push(user1_socket, "chat:typing", %{})

    assert_broadcast "chat:user_typing", %{user_id: "user-1"}
  end
end
